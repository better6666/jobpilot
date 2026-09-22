package com.jobpilot.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.system.ConfigService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.Locale;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** 攒着还没上报成功的投递次数。失败时加回去，下次投递成功时接着报 */
    private final AtomicInteger pendingReports = new AtomicInteger();

    /** 单线程：上报顺序即投递顺序，也不会并发进 flushReports */
    private final ExecutorService reportExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "license-report");
        t.setDaemon(true);
        return t;
    });

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
        // 永远发实时算出来的指纹，不发库里的旧值——库里那份会被整体拷走到另一台机器，
        // 发旧值就等于把"一卡一机"绕过了（实测：拷 Application Support 目录即可白嫖）
        String deviceId = deviceFingerprint();

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
        // 换卡就是新额度，上一张卡攒着没报上去的次数不能算到新卡头上
        pendingReports.set(0);
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
                Map.of("token", record.getToken(), "device_id", deviceFingerprint()));
        if (node == null) {
            throw new LicenseClient.LicenseUnavailableException("无法连接卡密服务端，请稍后再试");
        }
        record.setToken(null);
        record.setLastVerifyOkAt(null);
        record.setExpiresAt(null);
        record.setRemainingDays(null);
        record.setQuotaTotal(null);
        record.setQuotaRemaining(null);
        record.setQuotaUsed(null);
        pendingReports.set(0);
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

    /**
     * 平台 AI 中转的调用凭证（token + device_id）。
     *
     * <p>给 {@code AiService.chatPlatform} 用：平台模式下客户端只带这两个值
     * 去请求服务端代理，中转的 key 全程留在服务端。没激活就返回 null，
     * 调用方据此退回固定话术。
     */
    public Map<String, String> proxyCredentials() {
        LicenseRecord record = load();
        if (record == null || !notBlank(record.getToken()) || !notBlank(record.getDeviceId())) {
            return null;
        }
        return Map.of("token", record.getToken(), "device_id", deviceFingerprint());
    }

    /**
     * 上报投递次数（次数卡扣减）。投递成功一次调一次。
     *
     * <p>三条设计约束，都是被"投递不能被卡密拖住"逼出来的：
     * <ul>
     *   <li><b>异步</b>：跑批跑在浏览器 dispatcher 线程上，同步等一次跨境 HTTP
     *       会把每张卡片的间隔拉长一倍，节奏一规律就是机器特征。所以只累加计数、
     *       丢给单线程 executor 去发。</li>
     *   <li><b>失败不丢</b>：服务端不可达时把计数放回去，下次投递成功时接着报。
     *       反过来（宁可少报不可漏报）才是对卖卡的一方公平——客户端断网不能
     *       变成免费用。</li>
     *   <li><b>失败不打断投递</b>：上报失败只记日志。卡是不是真不能用了由心跳
     *       和 {@link #exhausted()} 判断，那一侧已经有宽限逻辑。</li>
     * </ul>
     *
     * <p>时长卡/试用卡不报：服务端对非次数卡一律回 400，每投一个都白跑一趟。
     * 只在这张卡的类型已经确认不是 quota 时跳过——类型还没拿到（null）时照报，
     * 让服务端去判，免得漏记。
     */
    public void reportUsage(int n) {
        if (n <= 0) {
            return;
        }
        pendingReports.addAndGet(n);
        try {
            reportExecutor.execute(this::flushReports);
        } catch (RejectedExecutionException e) {
            // 已经在关机了，计数留着也没用
            log.debug("上报线程已关闭，丢弃 {} 次投递上报", n);
        }
    }

    /**
     * 是否该把用户挡在激活页外——打开软件先看激活码就是靠这个。
     *
     * <p>只在"确实需要一张卡"时为真：{@link LicenseState#UNACTIVATED} /
     * {@link LicenseState#EXPIRED} / {@link LicenseState#REVOKED}。
     * <b>不含</b> {@code NETWORK_BLOCKED} 和 {@code GRACE}——那两种是服务端
     * 暂时连不上（网络抖动、CF 抽风），把人锁在界面外比让他多看几秒旧数据
     * 糟糕得多。fail-open 同理，直接放行。
     */
    public boolean needsActivation() {
        if (!properties.isEnabled() || properties.isFailOpen()) {
            return false;
        }
        LicenseState state = status().getState();
        return state == LicenseState.UNACTIVATED
                || state == LicenseState.EXPIRED
                || state == LicenseState.REVOKED;
    }

    /** 跑批中每次投递前看一眼：卡是不是已经不能用了。自用模式和 fail-open 下一律 false。 */
    public boolean exhausted() {
        if (!properties.isEnabled() || properties.isFailOpen()) {
            return false;
        }
        return !status().isAllowed();
    }

    // ---------------------------------------------------------------- internal

    /** 把攒下的次数一次性发出去。单线程 executor 保证不会并发进这个方法。 */
    private void flushReports() {
        int n = pendingReports.getAndSet(0);
        if (n <= 0) {
            return;
        }
        try {
            doReport(n);
        } catch (Exception e) {
            // 放回去等下次投递成功时重试；这里不能抛，executor 会吞掉后续任务
            pendingReports.addAndGet(n);
            log.warn("投递次数上报失败（{} 次，将稍后重试）: {}", n, e.toString());
        }
    }

    private void doReport(int n) {
        if (!properties.isEnabled()) {
            return;
        }
        LicenseRecord record = load();
        if (record == null || !notBlank(record.getToken()) || !notBlank(record.getDeviceId())) {
            return;
        }
        String type = record.getType();
        if (type != null && !"quota".equals(type)) {
            return;
        }
        JsonNode node = licenseClient.post(properties.getApiBase(), "/report", Map.of(
                "token", record.getToken(),
                "device_id", deviceFingerprint(),
                "n", n));
        if (node == null) {
            // 网络不可达：抛出去让 flushReports 把计数放回去
            throw new LicenseClient.LicenseUnavailableException("卡密服务端不可达");
        }
        JsonNode data = node.path("data");
        Long used = data.path("quota_used").isNull() ? null : data.path("quota_used").asLong();
        Long remaining = data.path("quota_remaining").isNull() ? null : data.path("quota_remaining").asLong();
        record.setQuotaUsed(used);
        record.setQuotaRemaining(remaining);
        record.setLastVerifyOkAt(Instant.now());
        save(record);
        log.info("投递次数已上报 | 本次 {} | 已用 {} | 剩余 {}", n, used, remaining);
        if (remaining != null && remaining <= 0) {
            publish(LicenseState.EXPIRED, "卡密次数已用完", record);
        } else {
            // 只刷新快照里的次数，判定状态不变——不然激活页的"剩余次数"
            // 要等下一次心跳才动，客户刚投完那几个看不到变化
            publish(status().getState(), status().getMessage(), record);
        }
    }

    @PreDestroy
    public void shutdown() {
        reportExecutor.shutdown();
    }

    private void doVerify(LicenseRecord record) {
        JsonNode node = licenseClient.post(properties.getApiBase(), "/verify",
                Map.of("token", record.getToken(), "device_id", deviceFingerprint()));
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
            // 设备不符不是"卡坏了"，是这台机器没资格用。单给一个状态，
            // 前端据此提示"重新激活"而不是"卡已失效"
            case "DEVICE_MISMATCH" -> LicenseState.DEVICE_MISMATCH;
            default -> LicenseState.REVOKED;
        };
    }

    private void applyServerFields(LicenseRecord record, JsonNode data) {
        record.setType(data.path("type").asText(null));
        record.setExpiresAt(data.path("expires_at").isNull() ? null : data.path("expires_at").asText());
        record.setRemainingDays(data.path("remaining_days").isNull() ? null : data.path("remaining_days").asLong());
        record.setQuotaTotal(data.path("quota_total").isNull() ? null : data.path("quota_total").asLong());
        record.setQuotaRemaining(data.path("quota_remaining").isNull() ? null : data.path("quota_remaining").asLong());
        if (!data.path("quota_used").isNull()) {
            record.setQuotaUsed(data.path("quota_used").asLong());
        }
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

    /**
     * 设备指纹：每次现算，不读库、不缓存、不写库。
     *
     * <p><b>为什么必须现算</b>：早先这值是首次激活时算一次然后落库，之后所有上行请求
     * 都发库里的旧值。于是别人只要把 {@code ~/Library/Application Support/JobPilot}
     * 整个目录拷到另一台 Mac，token 和设备号一起过去，服务端比对通过，"一卡一机"
     * 完全失效。改成现算之后，换机器指纹就变，服务端立刻能发现。
     *
     * <p>代价：用户改了主机名/用户名/换网卡之后指纹会变，需要重新激活一次。
     * 这个代价比一张卡被无限复制划算得多。
     *
     * <p>注意这仍然只是"机器特征"不是"硬件密钥"：知道算法的人可以改 hostname、
     * 用户名、MAC 去凑同一个指纹。要彻底防住得用 macOS Keychain / Secure Enclave
     * 那种不可导出的硬件绑定，成本高一个量级，见 README 的后续项。
     */
    private String deviceFingerprint() {
        String hw = hardwareUuid();
        // 有硬件 UUID 就只用它 + 系统用户名；拿不到才退回网卡/主机名那套。
        // 退回方案刻意不用网卡 MAC 当主输入：macOS 会枚举到 llw0 这种虚拟
        // 低延迟网卡，它的 MAC 是每个 Wi-Fi 随机生成的；"第一个启用的网卡"
        // 也会随 Wi-Fi/有线/手机热点的切换而变。早先就是这么写的，
        // 结果笔记本换个网络指纹就变，用户被自己锁死（实测踩过）。
        String raw = hw != null
                ? hw + "|" + System.getProperty("user.name")
                : hostname() + "|" + macAddress() + "|" + System.getProperty("user.name");
        return sha256(raw);
    }

    /** UUID 的字面形状，用来从各系统命令的输出里把它抠出来 */
    private static final java.util.regex.Pattern UUID_PATTERN = java.util.regex.Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * 操作系统提供的机器硬件 UUID。绑定到固件，不随网络、主机名、登录用户变化。
     *
     * <ul>
     *   <li>macOS: {@code ioreg -rd1 -c IOPlatformExpertDevice} 的 IOPlatformUUID</li>
     *   <li>Windows: 注册表 HKLM\SOFTWARE\Microsoft\Cryptography 的 MachineGuid</li>
     *   <li>Linux: /etc/machine-id，退化到 /sys/class/dmi/id/product_uuid</li>
     * </ul>
     * 都取不到返回 null，调用方退回网卡 + 主机名那套。
     */
    private String hardwareUuid() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            String out = null;
            if (os.contains("mac")) {
                out = readCommand("ioreg", "-rd1", "-c", "IOPlatformExpertDevice");
            } else if (os.contains("win")) {
                out = readCommand("reg", "query",
                        "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid");
            } else {
                Path etc = Path.of("/etc/machine-id");
                if (Files.isReadable(etc)) {
                    out = Files.readString(etc);
                } else {
                    Path dmi = Path.of("/sys/class/dmi/id/product_uuid");
                    if (Files.isReadable(dmi)) {
                        out = Files.readString(dmi);
                    }
                }
            }
            if (out == null || out.isBlank()) {
                return null;
            }
            java.util.regex.Matcher m = UUID_PATTERN.matcher(out);
            return m.find() ? m.group() : null;
        } catch (Exception e) {
            log.debug("取硬件 UUID 失败，退回网卡指纹: {}", e.getMessage());
            return null;
        }
    }

    /** 跑一条命令拿 stdout；失败/超时返回 null */
    private String readCommand(String... command) {
        try {
            Process p = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroy();
                return null;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 单测接缝：看一眼当前指纹，用来验证它稳定且不再由网卡决定 */
    String fingerprintForTest() {
        return deviceFingerprint();
    }

    /** entitlement 服务要用：拿本地存的激活记录 */
    LicenseRecord recordForTest() {
        return load();
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
