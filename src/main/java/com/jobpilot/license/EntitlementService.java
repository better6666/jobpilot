package com.jobpilot.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.system.SystemPaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * entitlement 与每日配额的唯一入口。
 *
 * <p>设计原则（用户明确要求）：
 * <ul>
 *   <li><b>统一判权</b>：页面和 Service 都调这里，不许到处 {@code if (plan.equals("advanced"))}</li>
 *   <li><b>服务端是真相源</b>：plan / 功能开关 / 配额全从卡密服务端拉，
 *       后台改完立刻生效，不用改客户端代码</li>
 *   <li><b>本地强校验配额</b>：当日 AI 分析次数、当日投递次数在本地计数，
 *       服务端只做兜底。断网时本地照样能拦住，不会因为连不上就无限用</li>
 *   <li><b>降级不锁死</b>：拉不到 entitlement 时用上次的缓存；没有缓存时按体验版
 *       保守放行——因为本就没激活，门禁那一层会拦住</li>
 * </ul>
 */
@Slf4j
@Service
public class EntitlementService {

    /** 「今天」按本机时区算，用户感知的"每天"是这个 */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final LicenseService licenseService;
    private final LicenseClient licenseClient;
    private final LicenseProperties properties;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    private volatile Entitlement cached;
    private volatile long lastFetchAt;

    public EntitlementService(LicenseService licenseService, LicenseClient licenseClient,
                              LicenseProperties properties, ObjectMapper objectMapper,
                              JdbcTemplate jdbcTemplate) {
        this.licenseService = licenseService;
        this.licenseClient = licenseClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void initTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS usage_counter (
                    counter_key TEXT NOT NULL,
                    day         TEXT NOT NULL,
                    count       INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (counter_key, day)
                )
                """);
        // 磁盘上的缓存：服务端临时不可达时至少还有一份可用的权益
        loadFromDisk().ifPresent(e -> cached = e);
    }

    // ------------------------------------------------------------------
    // 拉取与缓存
    // ------------------------------------------------------------------

    /** 当前 entitlement。可能来自缓存，保证不为 null */
    public Entitlement current() {
        Entitlement e = cached;
        return e != null ? e : fallback();
    }

    /** 强制刷新。启动时和心跳后调用；失败静默保留旧缓存 */
    public synchronized Entitlement refresh() {
        if (!properties.isEnabled()) {
            Entitlement e = new Entitlement();
            // 自用模式：全功能、无配额限制，方便自己调试
            e.setFeatures(Map.of("__all__", true));
            cached = e;
            return e;
        }
        LicenseRecord record = licenseService.recordForTest();
        if (record == null || !notBlank(record.getToken())) {
            Entitlement e = fallback();
            cached = e;
            return e;
        }
        try {
            JsonNode node = licenseClient.post(properties.getApiBase(), "/api/license/entitlement",
                    Map.of("token", record.getToken(), "device_id", licenseDeviceId()));
            if (node == null) {
                log.debug("entitlement 拉取失败（服务端不可达），沿用缓存");
                return current();
            }
            cached = parse(node.path("data"));
            lastFetchAt = System.currentTimeMillis();
            saveToDisk(cached);
            return cached;
        } catch (Exception e) {
            log.debug("entitlement 解析失败: {}", e.getMessage());
            return current();
        }
    }

    private Entitlement parse(JsonNode d) {
        Entitlement e = new Entitlement();
        e.setPlan(text(d, "plan", "trial"));
        e.setPlanName(text(d, "plan_name", "体验版"));
        e.setUsable(d.path("usable").asBoolean(false));
        e.setExpiresAt(text(d, "expires_at", null));
        e.setRemainingDays(d.path("remaining_days").asLong(0));
        if (!d.path("quota_total").isNull()) {
            e.setQuotaTotal(d.path("quota_total").asLong());
        }
        if (!d.path("quota_remaining").isNull()) {
            e.setQuotaRemaining(d.path("quota_remaining").asLong());
        }
        e.setRecommended(d.path("recommended").asBoolean(false));

        Map<String, Boolean> features = new LinkedHashMap<>();
        d.path("features").properties().forEach(en ->
                features.put(en.getKey(), en.getValue().asBoolean(false)));
        e.setFeatures(features);

        Map<String, Integer> quotas = new LinkedHashMap<>();
        d.path("quotas").properties().forEach(en ->
                quotas.put(en.getKey(), en.getValue().asInt(0)));
        e.setQuotas(quotas);
        return e;
    }

    /** 拉不到服务端时的兜底：按体验版算，且卡片不可用 */
    private Entitlement fallback() {
        Entitlement e = new Entitlement();
        e.setPlan("trial");
        e.setPlanName("体验版");
        e.setUsable(false);
        Map<String, Boolean> f = new LinkedHashMap<>();
        f.put("job_search", true); f.put("basic_filter", true); f.put("advanced_filter", false);
        f.put("ai_match", true); f.put("ai_explanation", false); f.put("auto_apply", true);
        f.put("smart_apply", false); f.put("multi_resume", false); f.put("ai_greeting", false);
        f.put("analytics", true); f.put("natural_language_rule", false);
        f.put("ab_test", false); f.put("ai_strategy", false);
        e.setFeatures(f);
        Map<String, Integer> q = new LinkedHashMap<>();
        q.put("max_daily_ai_analysis", 20); q.put("max_daily_apply", 20);
        q.put("max_resume_count", 1); q.put("max_job_profile_count", 1);
        e.setQuotas(q);
        return e;
    }

    // ------------------------------------------------------------------
    // 功能判定
    // ------------------------------------------------------------------

    /** 自用模式（没启用校验）一律放行 */
    public boolean allows(String feature) {
        if (!properties.isEnabled()) {
            return true;
        }
        Entitlement e = current();
        if (Boolean.TRUE.equals(e.getFeatures().get("__all__"))) {
            return true;
        }
        return e.has(feature) && e.isUsable();
    }

    public String plan() {
        return current().getPlan();
    }

    // ------------------------------------------------------------------
    // 每日配额
    // ------------------------------------------------------------------

    /** 今天已用的次数 */
    public int usedToday(String counterKey) {
        if (jdbcTemplate == null) {
            return 0;
        }
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT count FROM usage_counter WHERE counter_key = ? AND day = ?",
                    Integer.class, counterKey, today());
            return n == null ? 0 : n;
        } catch (EmptyResultDataAccessException e) {
            // 第一次使用当天没有计数行，这是正常的 0 次，不应中断投递。
            return 0;
        }
    }

    /** 今天还能用几次；配额为 0 或负数视为不限制，返回 Integer.MAX_VALUE */
    public int remainingToday(String counterKey, String quotaKey) {
        if (jdbcTemplate == null || !properties.isEnabled()) {
            return Integer.MAX_VALUE;
        }
        int quota = current().quota(quotaKey);
        if (quota <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, quota - usedToday(counterKey));
    }

    /** 记一次使用。返回本次之后的今日用量 */
    public int recordUse(String counterKey) {
        if (jdbcTemplate == null) {
            return 0;   // 单测里不建库，计数跳过
        }
        jdbcTemplate.update("""
                INSERT INTO usage_counter (counter_key, day, count) VALUES (?, ?, 1)
                ON CONFLICT(counter_key, day) DO UPDATE SET count = count + 1
                """, counterKey, today());
        // 返回本次之后的今日累计，调用方只知道"现在几次了"
        return usedToday(counterKey);
    }

    private static String today() {
        return LocalDate.now(ZONE).toString();
    }

    // ------------------------------------------------------------------
    // 磁盘缓存（服务端不可达时兜底）
    // ------------------------------------------------------------------

    private Path cacheFile() {
        return SystemPaths.dataDir().resolve("entitlement.json");
    }

    private void saveToDisk(Entitlement e) {
        try {
            Files.writeString(cacheFile(), objectMapper.writeValueAsString(Map.of(
                    "plan", e.getPlan(),
                    "plan_name", e.getPlanName(),
                    "usable", e.isUsable(),
                    "expires_at", e.getExpiresAt() == null ? "" : e.getExpiresAt(),
                    "remaining_days", e.getRemainingDays(),
                    "features", e.getFeatures(),
                    "quotas", e.getQuotas())));
        } catch (Exception ignore) {
            // 缓存写不进去不影响主流程
        }
    }

    private java.util.Optional<Entitlement> loadFromDisk() {
        try {
            Path f = cacheFile();
            if (!Files.isReadable(f)) {
                return java.util.Optional.empty();
            }
            JsonNode d = objectMapper.readTree(Files.readString(f)).path("data");
            if (d.isMissingNode()) {
                d = objectMapper.readTree(Files.readString(f));
            }
            return java.util.Optional.of(parse(d));
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    // ------------------------------------------------------------------

    private String licenseDeviceId() {
        return licenseService.fingerprintForTest();
    }

    private static String text(JsonNode n, String field, String def) {
        JsonNode v = n.path(field);
        return v.isTextual() && !v.asText().isBlank() ? v.asText() : def;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 启动后拉一次，之后按分钟兜底刷新 */
    @Scheduled(fixedDelayString = "${license.verify-interval-minutes:10}m", initialDelay = 20000)
    public void scheduledRefresh() {
        refresh();
    }

    public long lastFetchAt() {
        return lastFetchAt;
    }
}
