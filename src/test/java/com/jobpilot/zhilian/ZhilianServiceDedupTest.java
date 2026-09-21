package com.jobpilot.zhilian;

import com.jobpilot.browser.BrowserManager;
import com.jobpilot.delivery.Delivery;
import com.jobpilot.delivery.DeliveryMapper;
import com.jobpilot.ai.AiProperties;
import com.jobpilot.ai.AiService;
import com.jobpilot.ai.GreetingService;
import com.jobpilot.license.LicenseProperties;
import com.jobpilot.license.LicenseService;
import com.jobpilot.delivery.DeliveryOutcome;
import com.jobpilot.delivery.DeliveryStatus;
import com.jobpilot.delivery.RunCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 智联的去重/落库路径。共享内核由 BossServiceDedupTest 覆盖，这里只验证
 * 智联特有的两处：HR 标识为 null 时去重键仍然成立、投递上限的兜底措辞。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ZhilianServiceDedupTest {

    @Mock
    private ZhilianProperties properties;

    @Mock
    private ZhilianDriver driver;

    @Mock
    private DeliveryMapper deliveryMapper;

    @Mock
    private com.jobpilot.system.ConfigService configService;

    @Mock
    private BrowserManager browserManager;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Mock
    private LicenseService licenseService;

    private ZhilianService service;

    @BeforeEach
    void setUp() throws Exception {
        ZhilianProperties.ZhilianConfig config = new ZhilianProperties.ZhilianConfig();
        config.setDryRun(false);
        when(properties.get()).thenReturn(config);
        // mock 的 getJson 默认返回 null（不是 defaultValue），AI 配置得显式给一份关闭态的
        doReturn(new com.jobpilot.ai.AiConfig()).when(configService)
                .getJson(anyString(), eq(com.jobpilot.ai.AiConfig.class), any(com.jobpilot.ai.AiConfig.class));
        service = new ZhilianService(properties, driver, deliveryMapper, browserManager,
                new ZhilianOptions(new com.fasterxml.jackson.databind.ObjectMapper()),
                new RunCoordinator(),
                new GreetingService(new AiProperties(configService), new AiService(objectMapper), deliveryMapper,
                        licenseService, new LicenseProperties()),
                licenseService);
    }

    private ZhilianJobCard card() {
        ZhilianJobCard card = new ZhilianJobCard();
        card.setJobId("CC120812345J00382567890");
        card.setJobName("陈列设计");
        card.setBrandName("苏州某某服饰有限公司");
        return card;
    }

    private Delivery existing(String status) {
        Delivery delivery = new Delivery();
        delivery.setId(1L);
        delivery.setDeliveryStatus(status);
        return delivery;
    }

    @Test
    void 已投递的岗位直接跳过不再点立即投递() {
        when(deliveryMapper.selectOne(any())).thenReturn(existing("已投递"));

        service.processCardForTest(card(), "陈列设计", properties.get(), null);

        verify(driver, never()).deliver(any(), any(), any(), any(), any());
        verify(deliveryMapper, never()).insert(any(Delivery.class));
        assertEquals(1, service.status().getSkipped());
    }

    @Test
    void 新岗位投递成功后insert且platform为zhilian() {
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(driver.deliver(any(), any(), any(), any(), any()))
                .thenReturn(DeliveryOutcome.delivered("简历已投递"));

        service.processCardForTest(card(), "陈列设计", properties.get(), null);

        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryMapper).insert(captor.capture());
        Delivery saved = captor.getValue();
        assertEquals("已投递", saved.getDeliveryStatus());
        assertEquals("zhilian", saved.getPlatform());
        assertEquals("CC120812345J00382567890", saved.getEncryptId());
        assertEquals("苏州某某服饰有限公司", saved.getBrandName());
        assertEquals("陈列设计", saved.getKeyword());
        // 智联没有 HR id，这一列就是空的
        assertNull(saved.getEncryptUserId());
    }

    @Test
    void 预演模式下driver拿到dryRun配置且落库为预演() {
        ZhilianProperties.ZhilianConfig config = properties.get();
        config.setDryRun(true);
        // 干跑与否是驱动层决定的，编排层的职责是把 dryRun 传下去、把 PREVIEW 记成"预演"
        when(driver.deliver(any(), any(), any(), any(), any()))
                .thenReturn(DeliveryOutcome.preview("预演"));

        service.processCardForTest(card(), "陈列设计", config, null);

        ArgumentCaptor<ZhilianProperties.ZhilianConfig> captor =
                ArgumentCaptor.forClass(ZhilianProperties.ZhilianConfig.class);
        verify(driver).deliver(any(), any(), captor.capture(), any(), any());
        assertTrue(captor.getValue().isDryRun());

        ArgumentCaptor<Delivery> saved = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryMapper).insert(saved.capture());
        assertEquals("预演", saved.getValue().getDeliveryStatus());
        assertEquals(1, service.status().getPreviewed());
    }

    @Test
    void 触发每日投递上限时停任务并记失败() {
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(driver.deliver(any(), any(), any(), any(), any()))
                .thenReturn(new DeliveryOutcome(DeliveryStatus.LIMIT, "今日投递已达上限", null));

        service.processCardForTest(card(), "陈列设计", properties.get(), null);

        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryMapper).insert(captor.capture());
        assertEquals("投递失败", captor.getValue().getDeliveryStatus());
        assertEquals("触发每日投递上限", captor.getValue().getFailReason());
        assertEquals(1, service.status().getFailed());
    }

    @Test
    void 打分只认职位名和经验其余规则组不命中() {
        ZhilianProperties.ZhilianConfig config = properties.get();
        com.jobpilot.delivery.ScoreRules rules = new com.jobpilot.delivery.ScoreRules();
        rules.setThreshold(10);
        rules.setJobRules(java.util.List.of(newRule("陈列", 60)));
        // 学历/行业/JD 字段全是 null，这三组规则一条都不该命中
        rules.setDegreeRules(java.util.List.of(newRule("大专", 100)));
        rules.setIndustryRules(java.util.List.of(newRule("服装", 100)));
        rules.setJdRules(java.util.List.of(newRule("陈列", 100)));
        config.setScoreRules(rules);
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(driver.deliver(any(), any(), any(), any(), any()))
                .thenReturn(DeliveryOutcome.delivered("简历已投递"));

        service.processCardForTest(card(), "陈列设计", config, null);

        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryMapper).insert(captor.capture());
        assertEquals("已投递", captor.getValue().getDeliveryStatus());
        assertEquals(60, captor.getValue().getScore());
    }

    private static com.jobpilot.delivery.ScoreRules.Rule newRule(String match, int score) {
        com.jobpilot.delivery.ScoreRules.Rule rule = new com.jobpilot.delivery.ScoreRules.Rule();
        rule.setMatch(match);
        rule.setScore(score);
        return rule;
    }
}
