package com.jobpilot.liepin;

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 猎聘的去重与落库路径。driver 被 mock 掉，不碰浏览器。
 *
 * 这条用例的意义不在猎聘本身，而在验证共享内核 DeliveryService 对第二个
 * 平台同样成立：Boss 那套"预演不挡真实投递、已投递必须挡住、上限停任务"
 * 的语义，猎聘一个字都没改就继承了。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LiepinServiceDedupTest {

    @Mock
    private LiepinProperties properties;

    @Mock
    private LiepinDriver driver;

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

    private LiepinService service;

    @BeforeEach
    void setUp() {
        LiepinProperties.LiepinConfig config = new LiepinProperties.LiepinConfig();
        config.setSayHi("您好");
        config.setDryRun(true);
        when(properties.get()).thenReturn(config);
        // mock 的 getJson 默认返回 null（不是 defaultValue），AI 配置得显式给一份关闭态的
        doReturn(new com.jobpilot.ai.AiConfig()).when(configService)
                .getJson(anyString(), eq(com.jobpilot.ai.AiConfig.class), any(com.jobpilot.ai.AiConfig.class));
        service = new LiepinService(properties, driver, deliveryMapper, browserManager,
                new LiepinOptions(new com.fasterxml.jackson.databind.ObjectMapper()),
                new RunCoordinator(),
                new GreetingService(new AiProperties(configService), new AiService(objectMapper), deliveryMapper,
                        licenseService, new LicenseProperties(), null),
                licenseService, null);
    }

    private LiepinJobCard card() {
        LiepinJobCard card = new LiepinJobCard();
        card.setJobId("12345678");
        card.setRecruiterId("998877");
        card.setJobName("陈列设计");
        card.setBrandName("某品牌");
        return card;
    }

    private Delivery existing(String status) {
        Delivery delivery = new Delivery();
        delivery.setId(1L);
        delivery.setDeliveryStatus(status);
        return delivery;
    }

    @Test
    void 已投递的岗位直接跳过不再碰浏览器() {
        when(deliveryMapper.selectOne(any())).thenReturn(existing("已投递"));

        service.processCardForTest(card(), "陈列设计", properties.get(), null);

        verify(driver, never()).deliver(any(), any(), any(), any(), any(), any());
        verify(deliveryMapper, never()).insert(any(Delivery.class));
        assertEquals(1, service.status().getSkipped());
    }

    @Test
    void 预演过的岗位真实投递时不被挡住() {
        when(deliveryMapper.selectOne(any())).thenReturn(existing("预演"));
        when(driver.deliver(any(), any(), any(), any(), any(), any()))
                .thenReturn(DeliveryOutcome.delivered("您好"));

        service.processCardForTest(card(), "陈列设计", properties.get(), null);

        verify(driver).deliver(any(), any(), any(), any(), any(), any());
        verify(deliveryMapper).updateById(any(Delivery.class));
        verify(deliveryMapper, never()).insert(any(Delivery.class));
        assertEquals(1, service.status().getDelivered());
        assertEquals(0, service.status().getSkipped());
    }

    @Test
    void 新岗位投递成功后insert并落到liepin平台() {
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(driver.deliver(any(), any(), any(), any(), any(), any()))
                .thenReturn(DeliveryOutcome.delivered("您好"));

        service.processCardForTest(card(), "陈列设计", properties.get(), null);

        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryMapper).insert(captor.capture());
        Delivery saved = captor.getValue();
        assertEquals("liepin", saved.getPlatform());
        assertEquals("已投递", saved.getDeliveryStatus());
        assertEquals("12345678", saved.getEncryptId());
        assertEquals("998877", saved.getEncryptUserId());
        assertEquals("陈列设计", saved.getJobName());
        assertEquals("某品牌", saved.getBrandName());
    }

    @Test
    void 投递失败允许重试并记录原因() {
        when(deliveryMapper.selectOne(any())).thenReturn(existing("投递失败"));
        when(driver.deliver(any(), any(), any(), any(), any(), any()))
                .thenReturn(DeliveryOutcome.failed("未找到沟通按钮"));

        service.processCardForTest(card(), "陈列设计", properties.get(), null);

        verify(deliveryMapper).updateById(any(Delivery.class));
        assertEquals(1, service.status().getFailed());
        assertEquals(0, service.status().getSkipped());
    }

    /**
     * 聊天窗没出现就是没投出去，必须记失败、不算投递数。
     *
     * <p>2026-09-27 的实投里这里判成了成功：猎聘换了 IM 组件、旧选择器全失配，
     * 日志连着 36 条"已打招呼"，用户打开猎聘只看到 4 个会话。宁可报失败让用户
     * 重跑，也不能报成功——报上去的投递数必须是真实沟通数。
     */
    @Test
    void 聊天窗没出现时记失败而不是记已投递() {
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(driver.deliver(any(), any(), any(), any(), any(), any()))
                .thenReturn(DeliveryOutcome.failed("点击后聊天窗没有出现，沟通未确认（可能被弹窗挡住或平台限制）"));

        service.processCardForTest(card(), "陈列设计", properties.get(), null);

        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryMapper).insert(captor.capture());
        assertEquals("投递失败", captor.getValue().getDeliveryStatus());
        assertTrue(captor.getValue().getFailReason().contains("聊天窗"), captor.getValue().getFailReason());
        assertEquals(0, service.status().getDelivered());
        assertEquals(1, service.status().getFailed());
    }

    @Test
    void 打分不达标记已过滤() {
        LiepinProperties.LiepinConfig config = properties.get();
        com.jobpilot.delivery.ScoreRules rules = new com.jobpilot.delivery.ScoreRules();
        rules.setThreshold(50);
        rules.setJobRules(List.of(newScoreRule("陈列设计", 1)));
        config.setScoreRules(rules);

        service.processCardForTest(card(), "陈列设计", config, null);

        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryMapper).insert(captor.capture());
        assertEquals("已过滤", captor.getValue().getDeliveryStatus());
        assertEquals(1, captor.getValue().getScore());
        verify(driver, never()).deliver(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 触发每日上限时停任务并记失败() {
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(driver.deliver(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DeliveryOutcome(DeliveryStatus.LIMIT, "今日沟通已达上限", null));

        service.processCardForTest(card(), "陈列设计", properties.get(), null);

        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryMapper).insert(captor.capture());
        assertEquals("投递失败", captor.getValue().getDeliveryStatus());
        assertEquals("触发每日投递上限", captor.getValue().getFailReason());
    }

    @Test
    void 连续失败到上限就停任务_中间成功一次会清零() {
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(driver.deliver(any(), any(), any(), any(), any(), any()))
                .thenReturn(DeliveryOutcome.failed("点击后聊天窗没有出现"));
        int limit = com.jobpilot.delivery.DeliveryService.MAX_CONSECUTIVE_FAILURES;

        for (int i = 0; i < limit - 1; i++) {
            service.processCardForTest(card(), "陈列设计", properties.get(), null);
        }
        assertTrue(service.status().getLogs().stream().noneMatch(l -> l.contains("任务停止")));

        // 成功一次清零：再失败 limit-1 次也不该停
        when(driver.deliver(any(), any(), any(), any(), any(), any()))
                .thenReturn(DeliveryOutcome.delivered("您好"));
        service.processCardForTest(card(), "陈列设计", properties.get(), null);
        when(driver.deliver(any(), any(), any(), any(), any(), any()))
                .thenReturn(DeliveryOutcome.failed("点击后聊天窗没有出现"));
        for (int i = 0; i < limit - 1; i++) {
            service.processCardForTest(card(), "陈列设计", properties.get(), null);
        }
        assertTrue(service.status().getLogs().stream().noneMatch(l -> l.contains("任务停止")));

        service.processCardForTest(card(), "陈列设计", properties.get(), null);
        assertTrue(service.status().getLogs().stream().anyMatch(l -> l.contains("连续 " + limit + " 个岗位投递失败")));
    }

    private static com.jobpilot.delivery.ScoreRules.Rule newScoreRule(String match, int score) {
        com.jobpilot.delivery.ScoreRules.Rule rule = new com.jobpilot.delivery.ScoreRules.Rule();
        rule.setMatch(match);
        rule.setScore(score);
        return rule;
    }
}
