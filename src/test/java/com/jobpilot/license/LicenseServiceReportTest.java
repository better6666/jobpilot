package com.jobpilot.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.system.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 投递次数上报（次数卡扣减）。
 *
 * 这一块是"卖出去的卡真能收住"的唯一一环：服务端 /report 早就写好了，
 * 但客户端不调用的话 quota_used 永远是 0，次数卡等于无限用。
 *
 * 覆盖：正常上报、剩余为 0 转 EXPIRED、时长卡不白跑、不可达时计数不丢、
 * 未激活不上报，以及 exhausted() 在自用/fail-open 下不误伤。
 * 上报是异步的，所以一律用 timeout(...) 等 executor 跑完。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LicenseServiceReportTest {

    private static final String API_BASE = "https://license.test";

    @Mock
    private ConfigService configService;

    @Mock
    private LicenseClient licenseClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LicenseProperties properties;
    private LicenseService service;

    @BeforeEach
    void setUp() {
        properties = new LicenseProperties();
        properties.setEnabled(true);
        properties.setApiBase(API_BASE);
        service = new LicenseService(properties, configService, licenseClient, objectMapper);
    }

    private JsonNode json(String raw) throws Exception {
        return objectMapper.readTree(raw);
    }

    private void givenStoredRecord(LicenseRecord record) {
        when(configService.getJson(eq(ConfigService.LICENSE_KEY), eq(LicenseRecord.class), any()))
                .thenReturn(record);
    }

    /** 一张已激活的次数卡，且心跳刚确认过（状态 ACTIVE） */
    private LicenseRecord quotaRecord() {
        LicenseRecord record = new LicenseRecord();
        record.setCardKey("GK-QUOTA-1");
        record.setToken("tok-quota");
        record.setDeviceId("dev-1");
        record.setType("quota");
        record.setQuotaTotal(100L);
        record.setQuotaRemaining(100L);
        return record;
    }

    private void givenActive() throws Exception {
        LicenseRecord record = quotaRecord();
        givenStoredRecord(record);
        when(licenseClient.post(eq(API_BASE), eq("/verify"), anyMap())).thenReturn(json("""
                {"success":true,"data":{"type":"quota","quota_total":100,"quota_used":0,
                 "quota_remaining":100}}
                """));
        service.init();
    }

    /** 等一小会儿再断言"从没发过"——异步上报不能只靠一瞬间 */
    private void assertNeverReported() throws Exception {
        Thread.sleep(600);
        verify(licenseClient, never()).post(eq(API_BASE), eq("/report"), anyMap());
    }

    @Test
    void 投递成功一次就上报一次() throws Exception {
        givenActive();
        when(licenseClient.post(eq(API_BASE), eq("/report"), anyMap())).thenReturn(json("""
                {"success":true,"data":{"reported":1,"quota_used":1,"quota_remaining":99}}
                """));

        service.reportUsage(1);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(licenseClient, timeout(2000)).post(eq(API_BASE), eq("/report"), captor.capture());
        // device_id 同样是实时指纹，不是库里那个 "dev-1"（见 LicenseServiceDeviceBindingTest）
        assertThat(captor.getValue()).containsEntry("token", "tok-quota").containsEntry("n", 1);
        assertThat(captor.getValue().get("device_id").toString())
                .isNotEqualTo("dev-1")
                .matches("[0-9a-f]{64}");
        // 上报结果要落到状态里，激活页的"剩余次数"才是真的
        assertEquals(99L, service.status().getQuotaRemaining());
    }

    @Test
    void 剩余次数归零后状态转EXPIRED跑批随即可停() throws Exception {
        givenActive();
        when(licenseClient.post(eq(API_BASE), eq("/report"), anyMap())).thenReturn(json("""
                {"success":true,"data":{"reported":1,"quota_used":100,"quota_remaining":0}}
                """));

        service.reportUsage(1);

        verify(licenseClient, timeout(2000)).post(eq(API_BASE), eq("/report"), anyMap());
        assertEquals(LicenseState.EXPIRED, service.status().getState());
        assertFalse(service.status().isAllowed());
        assertTrue(service.exhausted());
    }

    @Test
    void 时长卡不上报不白跑服务端() throws Exception {
        LicenseRecord timeCard = quotaRecord();
        timeCard.setType("time");
        givenStoredRecord(timeCard);
        when(licenseClient.post(eq(API_BASE), eq("/verify"), anyMap())).thenReturn(json("""
                {"success":true,"data":{"type":"time","remaining_days":30}}
                """));
        service.init();

        service.reportUsage(1);

        assertNeverReported();
    }

    @Test
    void 服务端不可达时计数不丢下次补报() throws Exception {
        givenActive();
        when(licenseClient.post(eq(API_BASE), eq("/report"), anyMap()))
                .thenReturn(null)
                .thenReturn(json("""
                        {"success":true,"data":{"reported":2,"quota_used":2,"quota_remaining":98}}
                        """));

        service.reportUsage(1);
        // 第一次失败，计数被放回去
        verify(licenseClient, timeout(2000)).post(eq(API_BASE), eq("/report"), anyMap());
        service.reportUsage(1);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(licenseClient, timeout(2000).times(2)).post(eq(API_BASE), eq("/report"), captor.capture());
        // 两次投递合成一次上报，不断网期间漏掉的那次补回来了
        assertEquals(2, captor.getValue().get("n"));
        assertEquals(98L, service.status().getQuotaRemaining());
    }

    @Test
    void 没激活就不上报() throws Exception {
        givenStoredRecord(null);
        service.init();

        service.reportUsage(3);

        assertNeverReported();
    }

    @Test
    void 非正数直接忽略不发请求() throws Exception {
        givenActive();

        service.reportUsage(0);
        service.reportUsage(-2);

        assertNeverReported();
    }

    @Test
    void 自用模式下不判耗尽() {
        properties.setEnabled(false);
        givenStoredRecord(null);

        service.init();

        assertFalse(service.exhausted());
    }

    @Test
    void failOpen下次数用完也不停投() throws Exception {
        properties.setFailOpen(true);
        givenActive();
        when(licenseClient.post(eq(API_BASE), eq("/report"), anyMap())).thenReturn(json("""
                {"success":true,"data":{"reported":1,"quota_used":100,"quota_remaining":0}}
                """));

        service.reportUsage(1);
        verify(licenseClient, timeout(2000)).post(eq(API_BASE), eq("/report"), anyMap());

        assertFalse(service.exhausted());
    }

    @Test
    void 卡密不能用时exhausted为真() throws Exception {
        givenActive();
        when(licenseClient.post(eq(API_BASE), eq("/verify"), anyMap()))
                .thenThrow(new LicenseClient.LicenseServerException(403, "QUOTA_EXHAUSTED", "卡密次数已用完"));

        service.heartbeat();

        assertTrue(service.exhausted());
    }
}
