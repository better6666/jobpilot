package com.jobpilot.shixiseng;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.ai.AiConfig;
import com.jobpilot.ai.AiProperties;
import com.jobpilot.ai.AiService;
import com.jobpilot.ai.GreetingService;
import com.jobpilot.browser.BrowserManager;
import com.jobpilot.delivery.Delivery;
import com.jobpilot.delivery.DeliveryMapper;
import com.jobpilot.delivery.DeliveryOutcome;
import com.jobpilot.delivery.DeliveryStatus;
import com.jobpilot.delivery.RunCoordinator;
import com.jobpilot.delivery.ScoreRules;
import com.jobpilot.license.LicenseProperties;
import com.jobpilot.license.LicenseService;
import com.jobpilot.system.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 实习僧的去重/落库路径。共享内核由 BossServiceDedupTest 覆盖，这里只验证
 * 实习僧特有的两处：HR 标识为 null 时去重键仍然成立、JD 规则组要等详情页
 * 回填后才可能命中（列表阶段 jdRules 一律不命中）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShixisengServiceDedupTest {

    @Mock
    private ShixisengProperties properties;

    @Mock
    private ShixisengDriver driver;

    @Mock
    private DeliveryMapper deliveryMapper;

    @Mock
    private ConfigService configService;

    @Mock
    private BrowserManager browserManager;

    @Mock
    private LicenseService licenseService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ShixisengService service;

    @BeforeEach
    void setUp() {
        ShixisengProperties.ShixisengConfig config = new ShixisengProperties.ShixisengConfig();
        config.setDryRun(false);
        when(properties.get()).thenReturn(config);
        // mock 的 getJson 默认返回 null（不是 defaultValue），AI 配置得显式给一份关闭态的
        doReturn(new AiConfig()).when(configService)
                .getJson(anyString(), eq(AiConfig.class), any(AiConfig.class));
        service = new ShixisengService(properties, driver, deliveryMapper, browserManager,
                new ShixisengOptions(objectMapper), new RunCoordinator(),
                new GreetingService(new AiProperties(configService), new AiService(objectMapper),
                        deliveryMapper, licenseService, new LicenseProperties(), null),
                licenseService, null);
    }

    private ShixisengJobCard card() {
        ShixisengJobCard card = new ShixisengJobCard();
        card.setJobId("inn_h5ultjyv1tmx");
        card.setJobName("平面（实习）");
        card.setBrandName("宝时得");
        card.setCityName("苏州");
        return card;
    }

    private Delivery existing(String status) {
        Delivery delivery = new Delivery();
        delivery.setId(1L);
        delivery.setDeliveryStatus(status);
        return delivery;
    }

    @Test
    void 已投递的岗位直接跳过不再开详情页() {
        when(deliveryMapper.selectOne(any())).thenReturn(existing("已投递"));

        service.processCardForTest(card(), "视觉设计", properties.get(), null);

        verify(driver, never()).deliver(any(), any(), any(), any(), any());
        verify(deliveryMapper, never()).insert(any(Delivery.class));
        assertThat(service.status().getSkipped()).isEqualTo(1);
    }

    @Test
    void 新岗位投递成功后insert且platform为shixiseng() {
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(driver.deliver(any(), any(), any(), any(), any()))
                .thenReturn(DeliveryOutcome.delivered("简历已投递"));

        service.processCardForTest(card(), "视觉设计", properties.get(), null);

        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryMapper).insert(captor.capture());
        Delivery saved = captor.getValue();
        assertThat(saved.getDeliveryStatus()).isEqualTo("已投递");
        assertThat(saved.getPlatform()).isEqualTo("shixiseng");
        assertThat(saved.getEncryptId()).isEqualTo("inn_h5ultjyv1tmx");
        assertThat(saved.getBrandName()).isEqualTo("宝时得");
        assertThat(saved.getKeyword()).isEqualTo("视觉设计");
        // 实习僧没有 HR 账号体系，这一列就是空的
        assertThat(saved.getEncryptUserId()).isNull();
    }

    @Test
    void 预演模式下driver拿到dryRun配置且落库为预演() {
        ShixisengProperties.ShixisengConfig config = properties.get();
        config.setDryRun(true);
        when(driver.deliver(any(), any(), any(), any(), any()))
                .thenReturn(DeliveryOutcome.preview("预演"));

        service.processCardForTest(card(), "视觉设计", config, null);

        ArgumentCaptor<ShixisengProperties.ShixisengConfig> captor =
                ArgumentCaptor.forClass(ShixisengProperties.ShixisengConfig.class);
        verify(driver).deliver(any(), any(), captor.capture(), any(), any());
        assertThat(captor.getValue().isDryRun()).isTrue();

        ArgumentCaptor<Delivery> saved = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryMapper).insert(saved.capture());
        assertThat(saved.getValue().getDeliveryStatus()).isEqualTo("预演");
        assertThat(service.status().getPreviewed()).isEqualTo(1);
    }

    @Test
    void 列表阶段JD规则组不命中因为详情页还没拉() {
        ShixisengProperties.ShixisengConfig config = properties.get();
        ScoreRules rules = new ScoreRules();
        rules.setThreshold(10);
        rules.setJobRules(List.of(newRule("平面", 60)));
        // 列表页拿不到 JD，jdRules 命中不了——只有驱动层开详情页回填后才可能
        rules.setJdRules(List.of(newRule("陈列", 100)));
        config.setScoreRules(rules);
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(driver.deliver(any(), any(), any(), any(), any()))
                .thenReturn(DeliveryOutcome.delivered("简历已投递"));

        service.processCardForTest(card(), "视觉设计", config, null);

        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryMapper).insert(captor.capture());
        assertThat(captor.getValue().getDeliveryStatus()).isEqualTo("已投递");
        assertThat(captor.getValue().getScore()).isEqualTo(60);
    }

    @Test
    void 职位名命中黑名单被一票否决时不投() {
        ShixisengProperties.ShixisengConfig config = properties.get();
        ScoreRules rules = new ScoreRules();
        rules.setTitleReject(List.of("平面"));
        config.setScoreRules(rules);
        when(deliveryMapper.selectOne(any())).thenReturn(null);

        service.processCardForTest(card(), "视觉设计", config, null);

        verify(driver, never()).deliver(any(), any(), any(), any(), any());
        assertThat(service.status().getFiltered()).isEqualTo(1);
    }

    private static ScoreRules.Rule newRule(String match, int score) {
        ScoreRules.Rule rule = new ScoreRules.Rule();
        rule.setMatch(match);
        rule.setScore(score);
        return rule;
    }
}
