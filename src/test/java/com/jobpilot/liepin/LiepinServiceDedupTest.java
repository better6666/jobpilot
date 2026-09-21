package com.jobpilot.liepin;

import com.jobpilot.browser.BrowserManager;
import com.jobpilot.delivery.Delivery;
import com.jobpilot.delivery.DeliveryMapper;
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
    private BrowserManager browserManager;

    private LiepinService service;

    @BeforeEach
    void setUp() {
        LiepinProperties.LiepinConfig config = new LiepinProperties.LiepinConfig();
        config.setSayHi("您好");
        config.setDryRun(true);
        when(properties.get()).thenReturn(config);
        service = new LiepinService(properties, driver, deliveryMapper, browserManager,
                new LiepinOptions(new com.fasterxml.jackson.databind.ObjectMapper()),
                new RunCoordinator());
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

        verify(driver, never()).deliver(any(), any(), any(), any(), any());
        verify(deliveryMapper, never()).insert(any(Delivery.class));
        assertEquals(1, service.status().getSkipped());
    }

    @Test
    void 预演过的岗位真实投递时不被挡住() {
        when(deliveryMapper.selectOne(any())).thenReturn(existing("预演"));
        when(driver.deliver(any(), any(), any(), any(), any()))
                .thenReturn(DeliveryOutcome.delivered("您好"));

        service.processCardForTest(card(), "陈列设计", properties.get(), null);

        verify(driver).deliver(any(), any(), any(), any(), any());
        verify(deliveryMapper).updateById(any(Delivery.class));
        verify(deliveryMapper, never()).insert(any(Delivery.class));
        assertEquals(1, service.status().getDelivered());
        assertEquals(0, service.status().getSkipped());
    }

    @Test
    void 新岗位投递成功后insert并落到liepin平台() {
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(driver.deliver(any(), any(), any(), any(), any()))
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
        when(driver.deliver(any(), any(), any(), any(), any()))
                .thenReturn(DeliveryOutcome.failed("未找到沟通按钮"));

        service.processCardForTest(card(), "陈列设计", properties.get(), null);

        verify(deliveryMapper).updateById(any(Delivery.class));
        assertEquals(1, service.status().getFailed());
        assertEquals(0, service.status().getSkipped());
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
        verify(driver, never()).deliver(any(), any(), any(), any(), any());
    }

    @Test
    void 触发每日上限时停任务并记失败() {
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(driver.deliver(any(), any(), any(), any(), any()))
                .thenReturn(new DeliveryOutcome(DeliveryStatus.LIMIT, "今日沟通已达上限", null));

        service.processCardForTest(card(), "陈列设计", properties.get(), null);

        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryMapper).insert(captor.capture());
        assertEquals("投递失败", captor.getValue().getDeliveryStatus());
        assertEquals("触发每日投递上限", captor.getValue().getFailReason());
    }

    private static com.jobpilot.delivery.ScoreRules.Rule newScoreRule(String match, int score) {
        com.jobpilot.delivery.ScoreRules.Rule rule = new com.jobpilot.delivery.ScoreRules.Rule();
        rule.setMatch(match);
        rule.setScore(score);
        return rule;
    }
}
