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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 授权状态机测试。
 *
 * 覆盖四条关键路径：激活成功、服务端明确拒绝、服务端不可达（宽限内/宽限外）、
 * 解绑与心跳的边界。用 Mockito 顶掉 ConfigService 与 LicenseClient，
 * 不连数据库也不发网络请求。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LicenseServiceTest {

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

    private LicenseRecord recordWithToken() {
        LicenseRecord record = new LicenseRecord();
        record.setCardKey("GK-OLD-KEY");
        record.setToken("tok-old");
        record.setDeviceId("dev-1");
        return record;
    }

    @Test
    void 激活成功进入ACTIVE并把token落库() throws Exception {
        givenStoredRecord(null);
        when(licenseClient.post(eq(API_BASE), eq("/activate"), anyMap())).thenReturn(json("""
                {"success":true,"data":{"token":"tok-new","type":"time",
                 "expires_at":"2026-10-20T00:00:00Z","remaining_days":30}}
                """));

        LicenseStatus status = service.activate(" gk-new-key ");

        assertEquals(LicenseState.ACTIVE, status.getState());
        assertTrue(status.isAllowed());
        // mask 取前 9 位 + ****
        assertEquals("GK-NEW-KE****", status.getCardKeyMasked());
        assertEquals(30L, status.getRemainingDays());

        ArgumentCaptor<LicenseRecord> captor = ArgumentCaptor.forClass(LicenseRecord.class);
        verify(configService, atLeastOnce()).setJson(eq(ConfigService.LICENSE_KEY), captor.capture());
        LicenseRecord saved = captor.getValue();
        assertEquals("GK-NEW-KEY", saved.getCardKey());
        assertEquals("tok-new", saved.getToken());
    }

    @Test
    void 激活被服务端拒绝时异常原样上抛给控制器() throws Exception {
        givenStoredRecord(null);
        when(licenseClient.post(eq(API_BASE), eq("/activate"), anyMap()))
                .thenThrow(new LicenseClient.LicenseServerException(402, "CARD_NOT_FOUND", "卡密不存在"));

        LicenseClient.LicenseServerException ex = assertThrows(LicenseClient.LicenseServerException.class,
                () -> service.activate("GK-BAD-KEY"));

        assertEquals("卡密不存在", ex.getMessage());
        assertEquals("CARD_NOT_FOUND", ex.getCode());
        assertFalse(service.isAllowed());
    }

    @Test
    void 启动时配置的卡密被拒才发布REVOKED() throws Exception {
        properties.setCardKey("GK-BAD-KEY");
        givenStoredRecord(null);
        when(licenseClient.post(eq(API_BASE), eq("/activate"), anyMap()))
                .thenThrow(new LicenseClient.LicenseServerException(402, "CARD_NOT_FOUND", "卡密不存在"));

        service.init();

        assertEquals(LicenseState.REVOKED, service.status().getState());
        assertFalse(service.isAllowed());
        assertEquals("卡密不存在", service.status().getMessage());
    }

    @Test
    void 到期与次数用尽映射为EXPIRED() throws Exception {
        properties.setCardKey("GK-EXPIRED");
        givenStoredRecord(null);
        when(licenseClient.post(eq(API_BASE), eq("/activate"), anyMap()))
                .thenThrow(new LicenseClient.LicenseServerException(402, "CARD_EXPIRED", "卡密已到期"));

        service.init();

        assertEquals(LicenseState.EXPIRED, service.status().getState());
        assertFalse(service.isAllowed());
    }

    @Test
    void 服务端不可达且无历史确认时进入NETWORK_BLOCKED() throws Exception {
        givenStoredRecord(null);
        when(licenseClient.post(eq(API_BASE), eq("/activate"), anyMap())).thenReturn(null);

        assertThrows(LicenseClient.LicenseUnavailableException.class,
                () -> service.activate("GK-ANY-KEY"));

        assertEquals(LicenseState.NETWORK_BLOCKED, service.status().getState());
        assertFalse(service.isAllowed());
    }

    @Test
    void 服务端不可达但宽限期内进入GRACE并继续放行() throws Exception {
        LicenseRecord record = recordWithToken();
        record.setLastVerifyOkAt(Instant.now().minus(1, ChronoUnit.HOURS));
        givenStoredRecord(record);
        when(licenseClient.post(eq(API_BASE), eq("/activate"), anyMap())).thenReturn(null);

        assertThrows(LicenseClient.LicenseUnavailableException.class,
                () -> service.activate("GK-ANY-KEY"));

        assertEquals(LicenseState.GRACE, service.status().getState());
        assertTrue(service.isAllowed());
    }

    @Test
    void 超过宽限期后同一场景转为NETWORK_BLOCKED() throws Exception {
        properties.setGraceHours(72);
        LicenseRecord record = recordWithToken();
        record.setLastVerifyOkAt(Instant.now().minus(100, ChronoUnit.HOURS));
        givenStoredRecord(record);
        when(licenseClient.post(eq(API_BASE), eq("/activate"), anyMap())).thenReturn(null);

        assertThrows(LicenseClient.LicenseUnavailableException.class,
                () -> service.activate("GK-ANY-KEY"));

        assertEquals(LicenseState.NETWORK_BLOCKED, service.status().getState());
        assertFalse(service.isAllowed());
    }

    @Test
    void 心跳确认有效后保持ACTIVE() throws Exception {
        LicenseRecord record = recordWithToken();
        givenStoredRecord(record);
        when(licenseClient.post(eq(API_BASE), eq("/verify"), anyMap())).thenReturn(json("""
                {"success":true,"data":{"type":"time","remaining_days":29}}
                """));

        service.heartbeat();

        assertEquals(LicenseState.ACTIVE, service.status().getState());
        assertTrue(service.isAllowed());
        assertEquals(29L, service.status().getRemainingDays());
    }

    @Test
    void 心跳时服务端不可达进入宽限逻辑() throws Exception {
        LicenseRecord record = recordWithToken();
        record.setLastVerifyOkAt(Instant.now().minus(30, ChronoUnit.MINUTES));
        givenStoredRecord(record);
        when(licenseClient.post(eq(API_BASE), eq("/verify"), anyMap())).thenReturn(null);

        service.heartbeat();

        assertEquals(LicenseState.GRACE, service.status().getState());
        assertTrue(service.isAllowed());
    }

    @Test
    void 心跳在无token时不发请求() {
        givenStoredRecord(null);

        service.heartbeat();

        verify(licenseClient, never()).post(any(), any(), anyMap());
    }

    @Test
    void 启动时缓存token被服务端拒绝时降级REVOKED不挡住启动() {
        LicenseRecord record = recordWithToken();
        record.setLastVerifyOkAt(Instant.now());
        givenStoredRecord(record);
        when(licenseClient.post(eq(API_BASE), eq("/verify"), anyMap()))
                .thenThrow(new LicenseClient.LicenseServerException(401, "TOKEN_INVALID", "激活信息无效，请重新激活"));

        // 直接调用即断言：抛异常会让测试失败
        service.init();

        assertEquals(LicenseState.REVOKED, service.status().getState());
        assertFalse(service.isAllowed());
        assertEquals("激活信息无效，请重新激活", service.status().getMessage());
    }

    @Test
    void 心跳时token被服务端拒绝时降级REVOKED不抛异常() {
        givenStoredRecord(recordWithToken());
        when(licenseClient.post(eq(API_BASE), eq("/verify"), anyMap()))
                .thenThrow(new LicenseClient.LicenseServerException(401, "TOKEN_INVALID", "激活信息无效，请重新激活"));

        service.heartbeat();

        assertEquals(LicenseState.REVOKED, service.status().getState());
        assertFalse(service.isAllowed());
    }

    @Test
    void 解绑成功后清除token回到未激活() throws Exception {
        givenStoredRecord(recordWithToken());
        when(licenseClient.post(eq(API_BASE), eq("/unbind"), anyMap()))
                .thenReturn(json("{\"success\":true,\"data\":{}}"));

        LicenseStatus status = service.unbind();

        assertEquals(LicenseState.UNACTIVATED, status.getState());
        assertFalse(status.isAllowed());
        assertEquals("已解绑，可重新激活", status.getMessage());

        ArgumentCaptor<LicenseRecord> captor = ArgumentCaptor.forClass(LicenseRecord.class);
        verify(configService, atLeastOnce()).setJson(eq(ConfigService.LICENSE_KEY), captor.capture());
        assertNull(captor.getValue().getToken());
    }

    @Test
    void 没有已激活卡密时解绑不发请求() {
        givenStoredRecord(null);

        LicenseStatus status = service.unbind();

        assertEquals(LicenseState.UNACTIVATED, status.getState());
        assertEquals("当前没有已激活的卡密", status.getMessage());
        verify(licenseClient, never()).post(any(), any(), anyMap());
    }

    @Test
    void 未启用校验时启动即放行() {
        properties.setEnabled(false);

        service.init();

        assertEquals(LicenseState.OFF, service.status().getState());
        assertTrue(service.isAllowed());
        verify(licenseClient, never()).post(any(), any(), anyMap());
    }

    @Test
    void failOpen时未激活也放行() {
        properties.setFailOpen(true);
        givenStoredRecord(null);

        service.init();

        assertEquals(LicenseState.UNACTIVATED, service.status().getState());
        assertTrue(service.isAllowed());
    }

    @Test
    void 激活入参做去空白与大写归一化() throws Exception {
        givenStoredRecord(null);
        when(licenseClient.post(eq(API_BASE), eq("/activate"), anyMap())).thenReturn(json("""
                {"success":true,"data":{"token":"tok-new"}}
                """));

        service.activate("  gk-new-key\t");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(licenseClient).post(eq(API_BASE), eq("/activate"), captor.capture());
        assertEquals("GK-NEW-KEY", captor.getValue().get("card_key"));
    }

    @Test
    void 启动时有缓存token先走心跳确认() throws Exception {
        LicenseRecord record = recordWithToken();
        givenStoredRecord(record);
        when(licenseClient.post(eq(API_BASE), eq("/verify"), anyMap())).thenReturn(json("""
                {"success":true,"data":{"type":"time","remaining_days":30}}
                """));

        service.init();

        verify(licenseClient).post(eq(API_BASE), eq("/verify"), anyMap());
        assertEquals(LicenseState.ACTIVE, service.status().getState());
    }

    @Test
    void 心跳请求体只含token与设备号() throws Exception {
        givenStoredRecord(recordWithToken());
        when(licenseClient.post(eq(API_BASE), eq("/verify"), anyMap()))
                .thenReturn(json("{\"success\":true,\"data\":{}}"));

        service.heartbeat();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(licenseClient).post(eq(API_BASE), eq("/verify"), captor.capture());
        assertEquals(Map.of("token", "tok-old", "device_id", "dev-1"), captor.getValue());
    }
}
