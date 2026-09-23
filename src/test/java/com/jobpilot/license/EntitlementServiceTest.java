package com.jobpilot.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.system.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * entitlement 与每日配额。
 *
 * 覆盖用户明确要求的几条：权限统一判定、配额本地强校验、跨天重置、
 * 服务端不可达时降级不锁死、契约里没写的功能默认关闭。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EntitlementServiceTest {

    @Mock
    private ConfigService configService;
    @Mock
    private LicenseClient licenseClient;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LicenseProperties properties;
    private EntitlementService service;

    /** 直接塞一份 entitlement，绕开服务端，专注测判定与配额 */
    private void given(Entitlement e) {
        service = new EntitlementService(null, licenseClient, properties, objectMapper, jdbcTemplate) {
            @Override
            public Entitlement current() {
                return cached;
            }
            private final Entitlement cached = e;
        };
    }

    @BeforeEach
    void setUp() {
        properties = new LicenseProperties();
        properties.setEnabled(true);
        properties.setApiBase("https://license.test");
        doReturn(new com.jobpilot.ai.AiConfig()).when(configService)
                .getJson(anyString(), eq(com.jobpilot.ai.AiConfig.class), any(com.jobpilot.ai.AiConfig.class));
    }

    private Entitlement trial() {
        Entitlement e = new Entitlement();
        e.setPlan("trial");
        e.setPlanName("体验版");
        e.setUsable(true);
        Map<String, Boolean> f = new LinkedHashMap<>();
        f.put("job_search", true);
        f.put("basic_filter", true);
        f.put("advanced_filter", false);
        f.put("auto_apply", true);
        f.put("ai_greeting", false);
        e.setFeatures(f);
        Map<String, Integer> q = new LinkedHashMap<>();
        q.put("max_daily_apply", 10);
        q.put("max_daily_ai_analysis", 20);
        e.setQuotas(q);
        return e;
    }

    private EntitlementService real() {
        return new EntitlementService(null, licenseClient, properties, objectMapper, jdbcTemplate);
    }

    @Test
    void 体验版不给进阶功能基础功能照常() {
        given(trial());
        EntitlementService s = service;
        assertThat(s.allows("basic_filter")).isTrue();
        assertThat(s.allows("advanced_filter")).isFalse();
        assertThat(s.allows("ai_greeting")).isFalse();
    }

    @Test
    void 契约里没写的功能一律关闭不默认送() {
        given(trial());
        assertThat(service.allows("some_future_feature")).isFalse();
        assertThat(service.current().quota("max_resume_count")).isZero();
    }

    @Test
    void 卡片不可用时连基础功能也不给() {
        Entitlement e = trial();
        e.setUsable(false);
        given(e);
        assertThat(service.allows("job_search")).isFalse();
    }

    @Test
    void 自用模式下全部放行() {
        properties.setEnabled(false);
        given(trial());
        assertThat(service.allows("advanced_filter")).isTrue();
        assertThat(service.allows("whatever")).isTrue();
        assertThat(service.remainingToday("apply", "max_daily_apply"))
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void 自用模式刷新权益不会修改不可变默认映射() {
        properties.setEnabled(false);
        Entitlement refreshed = real().refresh();
        assertThat(refreshed.getFeatures()).containsEntry("__all__", true);
    }

    @Test
    void 配额为零视为不限制() {
        Entitlement e = trial();
        e.getQuotas().put("max_daily_apply", 0);
        given(e);
        assertThat(service.remainingToday("apply", "max_daily_apply"))
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void 当日用量达到配额后剩余为零() {
        Entitlement e = trial();
        given(e);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any()))
                .thenReturn(10);
        assertThat(service.remainingToday("apply", "max_daily_apply")).isZero();
    }

    @Test
    void 快照降级时按体验版保守放行() {
        EntitlementService s = real();
        // 没缓存、服务端也没配上 → fallback
        Entitlement e = s.current();
        assertThat(e.getPlan()).isEqualTo("trial");
        assertThat(e.isUsable()).isFalse();
        assertThat(e.quota("max_daily_apply")).isEqualTo(10);
        // 降级状态下基础功能不可用（卡片不可用），不会误放行
        assertThat(s.allows("basic_filter")).isFalse();
    }

    @Test
    void 配额计数写入数据库() {
        Entitlement e = trial();
        given(e);
        when(jdbcTemplate.update(anyString(), any(), any())).thenReturn(1);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any()))
                .thenReturn(3);
        int n = service.recordUse("apply");
        assertThat(n).isEqualTo(3);
        org.mockito.Mockito.verify(jdbcTemplate).update(anyString(), eq("apply"), anyString());
    }

    @Test
    void 没建库时计数不炸() {
        EntitlementService s = new EntitlementService(null, licenseClient, properties,
                objectMapper, null);
        given(trial());
        assertThat(s.recordUse("apply")).isZero();
        assertThat(s.usedToday("apply")).isZero();
    }

    @Test
    void 门槛不会把测试缝打崩() {
        given(trial());
        // usedToday 返回 null（库里没这行）要当 0 处理，不能 NPE
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any()))
                .thenReturn(null);
        assertThat(service.remainingToday("apply", "max_daily_apply")).isEqualTo(10);
    }
}
