package com.jobpilot.boss;

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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 去重与落库路径：预演不能挡住后续真实投递，已投递必须挡住。
 * deliver 被 mock 掉，不碰浏览器。
 *
 * 这条用例同时覆盖共享内核 DeliveryService：另外三个平台复用同一套
 * processCard/去重/落库逻辑，改内核时先跑这里。
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = Strictness.LENIENT)
class BossServiceDedupTest {

    @Mock
    private BossProperties properties;

    @Mock
    private BossDriver driver;

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

    private BossService service;

    @BeforeEach
    void setUp() {
        BossProperties.BossConfig config = new BossProperties.BossConfig();
        config.setSayHi("您好");
        config.setDryRun(true);
        when(properties.get()).thenReturn(config);
        // mock 的 getJson 默认返回 null（不是 defaultValue），AI 配置得显式给一份关闭态的
        doReturn(new com.jobpilot.ai.AiConfig()).when(configService)
                .getJson(anyString(), eq(com.jobpilot.ai.AiConfig.class), any(com.jobpilot.ai.AiConfig.class));
        service = new BossService(properties, driver, deliveryMapper, browserManager,
                new BossOptions(new com.fasterxml.jackson.databind.ObjectMapper()),
                new RunCoordinator(),
                new GreetingService(new AiProperties(configService), new AiService(objectMapper), deliveryMapper,
                        licenseService, new LicenseProperties()),
                licenseService);
    }

    private BossJobCard card() {
        BossJobCard card = new BossJobCard();
        card.setEncryptId("enc-1");
        card.setEncryptUserId("boss-1");
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

        verify(driver, never()).deliver(any(), any(), any(), anyBoolean(), any(), any());
        verify(deliveryMapper, never()).insert(any(Delivery.class));
        verify(deliveryMapper, never()).updateById(any(Delivery.class));
        assertEquals(1, service.status().getSkipped());
    }

    @Test
    void 预演过的岗位真实投递时不被挡住() {
        when(deliveryMapper.selectOne(any())).thenReturn(existing("预演"));
        when(driver.deliver(any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(DeliveryOutcome.delivered("您好"));

        service.processCardForTest(card(), "陈列设计", properties.get(), null);

        verify(driver).deliver(any(), any(), any(), anyBoolean(), any(), any());
        verify(deliveryMapper).updateById(any(Delivery.class));
        verify(deliveryMapper, never()).insert(any(Delivery.class));
        assertEquals(1, service.status().getDelivered());
        assertEquals(0, service.status().getSkipped());
    }

    @Test
    void 新岗位投递成功后insert且带上打招呼语() {
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(driver.deliver(any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(DeliveryOutcome.delivered("您好"));

        service.processCardForTest(card(), "陈列设计", properties.get(), null);

        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryMapper).insert(captor.capture());
        Delivery saved = captor.getValue();
        assertEquals("已投递", saved.getDeliveryStatus());
        assertEquals("您好", saved.getGreeting());
        assertEquals("某品牌", saved.getBrandName());
        assertEquals("陈列设计", saved.getKeyword());
        assertEquals("boss", saved.getPlatform());
        assertEquals("enc-1", saved.getEncryptId());
        assertEquals("boss-1", saved.getEncryptUserId());
    }

    @Test
    void 投递失败允许重试并记录原因() {
        when(deliveryMapper.selectOne(any())).thenReturn(existing("投递失败"));
        when(driver.deliver(any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(DeliveryOutcome.failed("未找到聊天输入框"));

        service.processCardForTest(card(), "陈列设计", properties.get(), null);

        verify(deliveryMapper).updateById(any(Delivery.class));
        assertEquals(1, service.status().getFailed());
        assertEquals(0, service.status().getSkipped());
    }

    @Test
    void 打分不达标记已过滤并带上分数() {
        BossProperties.BossConfig config = properties.get();
        com.jobpilot.delivery.ScoreRules rules = new com.jobpilot.delivery.ScoreRules();
        rules.setThreshold(50);
        rules.setJobRules(List.of(newScoreRule("陈列设计", 1)));
        config.setScoreRules(rules);

        service.processCardForTest(card(), "陈列设计", config, null);

        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryMapper).insert(captor.capture());
        assertEquals("已过滤", captor.getValue().getDeliveryStatus());
        assertEquals(1, captor.getValue().getScore());
        verify(driver, never()).deliver(any(), any(), any(), anyBoolean(), any(), any());
        assertEquals(1, service.status().getFiltered());
    }

    private static com.jobpilot.delivery.ScoreRules.Rule newScoreRule(String match, int score) {
        com.jobpilot.delivery.ScoreRules.Rule rule = new com.jobpilot.delivery.ScoreRules.Rule();
        rule.setMatch(match);
        rule.setScore(score);
        return rule;
    }

    @Test
    void 触发每日上限时停任务并记失败() {
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(driver.deliver(any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(new DeliveryOutcome(DeliveryStatus.LIMIT, "今日沟通已达上限", null));

        service.processCardForTest(card(), "陈列设计", properties.get(), null);

        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryMapper).insert(captor.capture());
        assertEquals("投递失败", captor.getValue().getDeliveryStatus());
        assertEquals("触发每日投递上限", captor.getValue().getFailReason());
        assertEquals(1, service.status().getFailed());
    }

    @Test
    void 投递成功要上报次数次数卡才扣得动() {
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(driver.deliver(any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(DeliveryOutcome.delivered("您好"));

        service.processCardForTest(card(), "陈列设计", properties.get(), null);

        verify(licenseService).reportUsage(1);
    }

    @Test
    void 预演和投递失败都不上报() {
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(driver.deliver(any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(DeliveryOutcome.failed("未找到聊天输入框"));

        service.processCardForTest(card(), "陈列设计", properties.get(), null);

        verify(licenseService, never()).reportUsage(anyInt());
    }

    @Test
    void 卡密次数用完时不再投直接停() {
        when(licenseService.exhausted()).thenReturn(true);
        when(deliveryMapper.selectOne(any())).thenReturn(null);

        service.processCardForTest(card(), "陈列设计", properties.get(), null);

        verify(driver, never()).deliver(any(), any(), any(), anyBoolean(), any(), any());
        verify(deliveryMapper, never()).insert(any(Delivery.class));
        assertEquals(0, service.status().getScanned());
        assertTrue(service.status().getLogs().stream()
                .anyMatch(l -> l.contains("卡密已不能使用")));
    }
}
