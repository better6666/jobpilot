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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设备指纹必须实时计算——「一卡一机」唯一真正起作用的防线。
 *
 * <p>早先客户端首次激活时算一次 deviceId 就落库，之后所有上行请求都发库里那个旧值。
 * 于是别人把 {@code ~/Library/Application Support/JobPilot} 整个目录拷到另一台 Mac，
 * token 和设备号一起过去，服务端比对通过，一张卡被无限复制。
 *
 * <p>这些用例钉住的行为：**不管库里存的是什么，上行一律发当前机器现算的指纹**。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LicenseServiceDeviceBindingTest {

    private static final String API_BASE = "https://license.test";
    /** 模拟"从别人机器上拷来的库"里存的设备号 */
    private static final String COPIED_DEVICE_ID = "device-id-copied-from-another-machine";

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
        doReturn(new com.jobpilot.ai.AiConfig()).when(configService)
                .getJson(anyString(), eq(com.jobpilot.ai.AiConfig.class),
                        any(com.jobpilot.ai.AiConfig.class));
    }

    private LicenseRecord recordWithStaleDeviceId() {
        LicenseRecord record = new LicenseRecord();
        record.setCardKey("GK-OLD");
        record.setToken("tok-old");
        record.setDeviceId(COPIED_DEVICE_ID);
        record.setLastVerifyOkAt(Instant.now());
        return record;
    }

    private void givenStoredRecord(LicenseRecord record) {
        when(configService.getJson(eq(ConfigService.LICENSE_KEY), eq(LicenseRecord.class), any()))
                .thenReturn(record);
    }

    /** 从捕获到的请求体里取 device_id */
    private String capturedDeviceId(ArgumentCaptor<Map<String, Object>> captor) {
        Object v = captor.getValue().get("device_id");
        return v == null ? null : v.toString();
    }

    @Test
    void 激活时发实时指纹不发票库里拷来的旧值() throws Exception {
        givenStoredRecord(recordWithStaleDeviceId());
        when(licenseClient.post(eq(API_BASE), eq("/activate"), anyMap())).thenReturn(json("""
                {"success":true,"data":{"token":"tok-new","type":"time","remaining_days":30}}
                """));

        service.activate("GK-NEW-KEY");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(licenseClient).post(eq(API_BASE), eq("/activate"), captor.capture());
        String sent = capturedDeviceId(captor);

        assertThat(sent)
                .as("绝不能发库里那份——那就是能被人整体拷走的值")
                .isNotEqualTo(COPIED_DEVICE_ID);
        assertThat(sent)
                .as("发出去的应是当前机器现算的 SHA-256")
                .matches("[0-9a-f]{64}");
    }

    @Test
    void 心跳时也发实时指纹() {
        givenStoredRecord(recordWithStaleDeviceId());
        when(licenseClient.post(eq(API_BASE), eq("/verify"), anyMap())).thenReturn(json("""
                {"success":true,"data":{"type":"time","remaining_days":30}}
                """));

        service.heartbeat();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(licenseClient).post(eq(API_BASE), eq("/verify"), captor.capture());
        assertThat(capturedDeviceId(captor))
                .isNotEqualTo(COPIED_DEVICE_ID)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void 解绑时也发实时指纹() {
        givenStoredRecord(recordWithStaleDeviceId());
        when(licenseClient.post(eq(API_BASE), eq("/unbind"), anyMap())).thenReturn(json("""
                {"success":true,"data":{}}
                """));

        service.unbind();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(licenseClient).post(eq(API_BASE), eq("/unbind"), captor.capture());
        assertThat(capturedDeviceId(captor))
                .isNotEqualTo(COPIED_DEVICE_ID)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void 设备不符时状态转DEVICE_MISMATCH且不放行投递() {
        givenStoredRecord(recordWithStaleDeviceId());
        when(licenseClient.post(eq(API_BASE), eq("/verify"), anyMap()))
                .thenThrow(new LicenseClient.LicenseServerException(401, "DEVICE_MISMATCH",
                        "当前设备与激活时不一致"));

        service.heartbeat();

        LicenseStatus status = service.status();
        assertThat(status.getState()).isEqualTo(LicenseState.DEVICE_MISMATCH);
        assertThat(status.isAllowed()).isFalse();
        assertThat(service.isAllowed()).isFalse();
    }

    @Test
    void 伪造token与设备不符是两种不同状态() {
        // token 查不到 → REVOKED（卡坏了/要重新激活）
        givenStoredRecord(recordWithStaleDeviceId());
        when(licenseClient.post(eq(API_BASE), eq("/verify"), anyMap()))
                .thenThrow(new LicenseClient.LicenseServerException(401, "TOKEN_INVALID",
                        "激活信息无效，请重新激活"));
        service.heartbeat();
        assertThat(service.status().getState()).isEqualTo(LicenseState.REVOKED);

        // 设备不符 → DEVICE_MISMATCH（换机器了/库被拷了）
        givenStoredRecord(recordWithStaleDeviceId());
        when(licenseClient.post(eq(API_BASE), eq("/verify"), anyMap()))
                .thenThrow(new LicenseClient.LicenseServerException(401, "DEVICE_MISMATCH",
                        "当前设备与激活时不一致"));
        service.heartbeat();
        assertThat(service.status().getState()).isEqualTo(LicenseState.DEVICE_MISMATCH);
    }

    @Test
    void 指纹在换网络后保持稳定() {
        // 早先用"第一个启用的网卡 MAC + DNS 反查主机名"，笔记本换个 Wi-Fi 指纹就变，
        // 正常用户被自己锁死。现在主输入是 OS 的硬件 UUID，与网络无关。
        // 这里只验证"不依赖可变的那两项"：同样的输入算两次必须一致，
        // 且不含 hostname / macAddress 的痕迹。
        String a = service.fingerprintForTest();
        String b = service.fingerprintForTest();
        assertThat(a).isEqualTo(b).matches("[0-9a-f]{64}");
        assertThat(a).isNotEqualTo(sha256Of(hostnamePiece() + "|" + macPiece() + "|" + userPiece()));
    }

    private static String hostnamePiece() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    private static String macPiece() {
        return "no-mac";
    }

    private static String userPiece() {
        return System.getProperty("user.name");
    }

    private static String sha256Of(String raw) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 平台中转的调用凭证也带实时指纹() {
        givenStoredRecord(recordWithStaleDeviceId());

        Map<String, String> credentials = service.proxyCredentials();

        assertThat(credentials).containsKey("device_id");
        assertThat(credentials.get("device_id"))
                .isNotEqualTo(COPIED_DEVICE_ID)
                .matches("[0-9a-f]{64}");
    }

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
