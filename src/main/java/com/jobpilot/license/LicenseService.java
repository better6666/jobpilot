package com.jobpilot.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.system.ConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 卡密校验核心。
 *
 * 生命周期：
 *   1. 启动：有缓存 token 先心跳确认；否则用配置里的卡密激活；都没有则进入未激活状态
 *   2. 心跳：每 verify-interval-minutes 分钟向服务端确认一次
 *   3. 宽限：服务端网络不可达时，若距上次确认未超过 grace-hours 仍放行（防 CF 抽风误杀）
 *
 * 状态与 token 落 config 表，重启不丢；设备指纹首次生成后固定，换绑由服务端限次。
 */
@Slf4j
@Service
@DependsOn("schemaInitializer")
@RequiredArgsConstructor
public class LicenseService {

    private final LicenseProperties properties;
    private final ConfigService configService;
    private final LicenseClient licenseClient;
    private final ObjectMapper objectMapper;

    private volatile LicenseStatus status;

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            publish(LicenseState.OFF, "未启用卡密校验（自用模式）", null);
            return;
        }
        LicenseRecord record = load();
        if (record != null && notBlank(record.getToken())) {
            try {
                doVerify(record);
            } catch (LicenseClient.LicenseServerException e) {
                // 服务端说 token 失效（换过库/卡被作废）不能挡住启动，
                // 降级成未激活态，用户在激活页重新填卡即可
                publish(mapServerCode(e.getCode()), e.getMessage(), record);
            }
            return;
        }
        String cardKey = notBlank(properties.getCardKey())
                ? properties.getCardKey()
                : (record != null ? record.getCardKey() : null);
        if (notBlank(cardKey)) {
            try {
                activate(cardKey);
            } catch (LicenseClient.LicenseServerException e) {
                publish(mapServerCode(e.getCode()), e.getMessage(), record);
            } catch (LicenseClient.LicenseUnavailableException e) {
                publish(networkState(record), e.getMessage(), record);
            }
            return;
        }
        publish(LicenseState.UNACTIVATED, "尚未填写卡密，请先激活", record);
    }

    /** 激活或换卡。服务端拒绝时抛出异常，由控制器转成用户可读信息。 */
    public synchronized LicenseStatus activate(String cardKey) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("未启用卡密校验");
        }
        String key = cardKey.trim().toUpperCase();
        LicenseRecord record = load();
        if (record == null) {
            record = new LicenseRecord();
        }
        String deviceId = ensureDeviceId(record);

        JsonNode node = licenseClient.post(properties.getApiBase(), "/activate",
                Map.of("card_key", key, "device_id", deviceId, "device_name", hostname()));
        if (node == null) {
            LicenseState state = networkState(record);
            publish(state, "无法连接卡密服务端，请检查网络或 license.api-base 配置", record);
            throw new LicenseClient.LicenseUnavailableException("无法连接卡密服务端，请检查网络或 license.api-base 配置");
        }
        JsonNode data = node.path("data");
        String token = data.path("token").asText(null);
        if (token == null || token.isBlank()) {
            publish(LicenseState.REVOKED, "激活响应异常（无 token）", record);
            throw new LicenseClient.LicenseServerException(500, "BAD_RESPONSE", "激活响应异常，请联系服务端");
        }
        record.setCardKey(key);
        record.setToken(token);
        record.setDeviceId(deviceId);
        record.setLastVerifyOkAt(Instant.now());
        applyServerFields(record, data);
        save(record);
        publish(LicenseState.ACTIVE, "卡密已激活", record);
        return snapshot();
    }

    /** 心跳。initialDelay 60s 避开启动高峰，之后按间隔循环。 */
    @Scheduled(fixedDelayString = "${license.verify-interval-minutes:10}m", initialDelay = 60000)
    public synchronized void heartbeat() {
        if (!properties.isEnabled()) {
            return;
        }
        LicenseRecord record = load();
        if (record == null || !notBlank(record.getToken())) {
            return;
        }
        try {
            doVerify(record);
        } catch (LicenseClient.LicenseServerException e) {
            // 运行中卡被作废/换库：更新状态并放行拦截，但不影响应用其他功能
            publish(mapServerCode(e.getCode()), e.getMessage(), record);
        }
    }

    /** 解绑当前设备（服务端限次：终身 3 次、每次冷却 7 天） */
    public synchronized LicenseStatus unbind() {
        LicenseRecord record = load();
        if (record == null || !notBlank(record.getToken())) {
            publish(LicenseState.UNACTIVATED, "当前没有已激活的卡密", record);
            return snapshot();
        }
        JsonNode node = licenseClient.post(properties.getApiBase(), "/unbind",
                Map.of("token", record.getToken(), "device_id", record.getDeviceId()));
        if (node == null) {
            throw new LicenseClient.LicenseUnavailableException("无法连接卡密服务端，请稍后再试");
        }
        record.setToken(null);
        record.setLastVerifyOkAt(null);
        record.setExpiresAt(null);
        record.setRemainingDays(null);
        record.setQuotaTotal(null);
        record.setQuotaRemaining(null);
        save(record);
        publish(LicenseState.UNACTIVATED, "已解绑，可重新激活", record);
        return snapshot();
    }

    public LicenseStatus status() {
        LicenseStatus current = this.status;
        if (current == null) {
            synchronized (this) {
                if (this.status == null) {
                    publish(LicenseState.UNACTIVATED, "尚未初始化", null);
                }
                current = this.status;
            }
        }
        return current;
    }

    public boolean isAllowed() {
        return status().isAllowed();
    }

    // ---------------------------------------------------------------- internal

    private void doVerify(LicenseRecord record) {
        JsonNode node = licenseClient.post(properties.getApiBase(), "/verify",
                Map.of("token", record.getToken(), "device_id", record.getDeviceId()));
        if (node == null) {
            LicenseState state = networkState(record);
            publish(state, state == LicenseState.GRACE
                    ? "卡密服务端暂不可达，宽限期内继续可用"
                    : "卡密服务端不可达且已超过宽限期", record);
            return;
        }
        record.setLastVerifyOkAt(Instant.now());
        applyServerFields(record, node.path("data"));
        save(record);
        publish(LicenseState.ACTIVE, "卡密有效", record);
    }

    private LicenseState networkState(LicenseRecord record) {
        if (record != null && record.getLastVerifyOkAt() != null
                && Duration.between(record.getLastVerifyOkAt(), Instant.now()).toHours() < properties.getGraceHours()) {
            return LicenseState.GRACE;
        }
        return LicenseState.NETWORK_BLOCKED;
    }

    private LicenseState mapServerCode(String code) {
        return switch (code) {
            case "CARD_EXPIRED", "QUOTA_EXHAUSTED" -> LicenseState.EXPIRED;
            default -> LicenseState.REVOKED;
        };
    }

    private void applyServerFields(LicenseRecord record, JsonNode data) {
        record.setType(data.path("type").asText(null));
        record.setExpiresAt(data.path("expires_at").isNull() ? null : data.path("expires_at").asText());
        record.setRemainingDays(data.path("remaining_days").isNull() ? null : data.path("remaining_days").asLong());
        record.setQuotaTotal(data.path("quota_total").isNull() ? null : data.path("quota_total").asLong());
        record.setQuotaRemaining(data.path("quota_remaining").isNull() ? null : data.path("quota_remaining").asLong());
    }

    private void publish(LicenseState state, String message, LicenseRecord record) {
        LicenseStatus snapshot = new LicenseStatus();
        snapshot.setEnabled(properties.isEnabled());
        snapshot.setState(state);
        snapshot.setMessage(message);
        snapshot.setAllowed(allowed(state));
        snapshot.setLastVerifyAt(record != null ? record.getLastVerifyOkAt() : null);
        snapshot.setExpiresAt(record != null ? record.getExpiresAt() : null);
        snapshot.setRemainingDays(record != null ? record.getRemainingDays() : null);
        snapshot.setQuotaTotal(record != null ? record.getQuotaTotal() : null);
        snapshot.setQuotaRemaining(record != null ? record.getQuotaRemaining() : null);
        snapshot.setType(record != null ? record.getType() : null);
        String cardKey = record != null ? record.getCardKey() : properties.getCardKey();
        snapshot.setCardKeyMasked(mask(cardKey));
        this.status = snapshot;
        log.info("卡密状态 | {} | {} | {}", state, message, snapshot.getCardKeyMasked());
    }

    private boolean allowed(LicenseState state) {
        if (!properties.isEnabled() || properties.isFailOpen()) {
            return true;
        }
        return state == LicenseState.ACTIVE || state == LicenseState.GRACE;
    }

    private LicenseStatus snapshot() {
        return status();
    }

    private String mask(String cardKey) {
        if (!notBlank(cardKey)) {
            return null;
        }
        String key = cardKey.trim();
        return key.length() > 9 ? key.substring(0, 9) + "****" : "****";
    }

    private LicenseRecord load() {
        return configService.getJson(ConfigService.LICENSE_KEY, LicenseRecord.class, null);
    }

    private void save(LicenseRecord record) {
        configService.setJson(ConfigService.LICENSE_KEY, record);
    }

    /** 设备指纹：hostname + MAC + 系统用户名 的哈希，首次生成后固定落库 */
    private String ensureDeviceId(LicenseRecord record) {
        if (notBlank(record.getDeviceId())) {
            return record.getDeviceId();
        }
        String raw = hostname() + "|" + macAddress() + "|" + System.getProperty("user.name");
        String id = sha256(raw);
        record.setDeviceId(id);
        save(record);
        return id;
    }

    private String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    private String macAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) {
                    continue;
                }
                byte[] mac = ni.getHardwareAddress();
                if (mac != null) {
                    StringBuilder sb = new StringBuilder();
                    for (byte b : mac) {
                        sb.append(String.format("%02x", b));
                    }
                    return sb.toString();
                }
            }
        } catch (Exception ignored) {
            // 拿不到 MAC 就退化成 "no-mac"，指纹仍然可用
        }
        return "no-mac";
    }

    private String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成设备指纹", e);
        }
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
