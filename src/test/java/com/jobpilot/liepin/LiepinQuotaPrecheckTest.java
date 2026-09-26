package com.jobpilot.liepin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.ai.AiConfig;
import com.jobpilot.ai.AiProperties;
import com.jobpilot.ai.AiService;
import com.jobpilot.ai.GreetingService;
import com.jobpilot.boss.BossDriver;
import com.jobpilot.boss.BossOptions;
import com.jobpilot.boss.BossProperties;
import com.jobpilot.boss.BossService;
import com.jobpilot.browser.BrowserManager;
import com.jobpilot.delivery.DeliveryMapper;
import com.jobpilot.delivery.DeliveryOutcome;
import com.jobpilot.delivery.RunCoordinator;
import com.jobpilot.delivery.RunStatus;
import com.jobpilot.license.Entitlement;
import com.jobpilot.license.EntitlementService;
import com.jobpilot.license.LicenseProperties;
import com.jobpilot.license.LicenseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 当日投递额度的计数口径与启动前预检。
 *
 * <p>真实事故：额度曾是五平台共用的一个 {@code apply} 计数器，Boss 一家把进阶版的
 * 150 吃满后，猎聘/智联点"开始"会在第一个岗位就被闸停掉，收尾日志还写着
 * "已手动停止。本次共投递 0 个岗位"——用户看到的就是"明明登录了却不投简历"。
 * 这里钉住两件事：计数按平台分开、额度用完压根不进浏览器。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LiepinQuotaPrecheckTest {

    @Mock
    private LiepinProperties liepinProperties;

    @Mock
    private LiepinDriver liepinDriver;

    @Mock
    private BossProperties bossProperties;

    @Mock
    private BossDriver bossDriver;

    @Mock
    private DeliveryMapper deliveryMapper;

    @Mock
    private com.jobpilot.system.ConfigService configService;

    @Mock
    private BrowserManager browserManager;

    @Mock
    private LicenseService licenseService;

    @Mock
    private EntitlementService entitlementService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        Entitlement entitlement = new Entitlement();
        entitlement.setUsable(true);
        entitlement.setQuotas(Map.of("max_daily_apply", 150));
        when(entitlementService.current()).thenReturn(entitlement);
        when(entitlementService.usedToday(anyString())).thenReturn(0);
        when(entitlementService.remainingToday(anyString(), anyString())).thenReturn(150);

        // 出厂默认是预演 + 各平台自带关键词/城市，这里统一收成"真投 + 全国"，
        // 让每个用例只表达自己要测的那一件事
        LiepinProperties.LiepinConfig liepin = new LiepinProperties.LiepinConfig();
        liepin.setDryRun(false);
        liepin.setCity("");
        when(liepinProperties.get()).thenReturn(liepin);

        BossProperties.BossConfig boss = new BossProperties.BossConfig();
        boss.setDryRun(false);
        boss.setCity("");
        boss.setSayHi("您好");
        boss.setKeywords(List.of("陈列设计"));
        when(bossProperties.get()).thenReturn(boss);

        // mock 的 getJson 默认返回 null（不是 defaultValue），AI 配置显式给一份关闭态的
        doReturn(new AiConfig()).when(configService)
                .getJson(anyString(), eq(AiConfig.class), any(AiConfig.class));
    }

    private LiepinService liepinService() {
        return new LiepinService(liepinProperties, liepinDriver, deliveryMapper, browserManager,
                new LiepinOptions(objectMapper), new RunCoordinator(), greetingService(),
                licenseService, entitlementService);
    }

    private BossService bossService() {
        return new BossService(bossProperties, bossDriver, deliveryMapper, browserManager,
                new BossOptions(objectMapper), new RunCoordinator(), greetingService(),
                licenseService, entitlementService);
    }

    private GreetingService greetingService() {
        return new GreetingService(new AiProperties(configService), new AiService(objectMapper),
                deliveryMapper, licenseService, new LicenseProperties(), entitlementService);
    }

    private LiepinJobCard card() {
        LiepinJobCard card = new LiepinJobCard();
        card.setJobId("9001");
        card.setRecruiterId("hr-1");
        card.setJobName("陈列设计");
        card.setBrandName("某服饰");
        return card;
    }

    // ------------------------------------------------------------------
    // 计数口径
    // ------------------------------------------------------------------

    @Test
    void 投递成功只记本平台自己的额度计数器() {
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(liepinDriver.deliver(any(), any(), any(), any(), any(), any()))
                .thenReturn(DeliveryOutcome.delivered("已沟通"));
        LiepinService service = liepinService();

        service.processCardForTest(card(), "陈列设计", liepinProperties.get(), null);

        verify(entitlementService).recordUse("apply:liepin");
        verify(entitlementService, never()).recordUse("apply");
    }

    @Test
    void 状态里的今日用量按本平台读() {
        when(entitlementService.usedToday("apply:liepin")).thenReturn(30);
        LiepinService service = liepinService();

        RunStatus status = service.status();

        assertEquals(30, status.getQuotaUsed());
        assertEquals(150, status.getQuotaLimit());
    }

    // ------------------------------------------------------------------
    // 启动前预检
    // ------------------------------------------------------------------

    @Test
    void 本平台额度用完时压根不进浏览器且说明是哪个平台() {
        when(entitlementService.remainingToday("apply:liepin", "max_daily_apply")).thenReturn(0);

        String error = liepinService().start();

        assertEquals("「猎聘」今日投递额度已用完（每平台 150 个）。"
                + "明天 0 点后自动恢复，升级套餐可提高额度，也可以先切别的平台投。", error);
        verify(browserManager, never()).submitAsync(any());
    }

    /** 额度按平台各算一份：Boss 用满了不影响猎聘照常起 */
    @Test
    void 一个平台用完不影响另一个平台() {
        when(entitlementService.remainingToday("apply:boss", "max_daily_apply")).thenReturn(0);
        when(entitlementService.remainingToday("apply:liepin", "max_daily_apply")).thenReturn(150);

        assertTrue(bossService().start().contains("额度已用完"));
        assertNull(liepinService().start());
        verify(browserManager).submitAsync(any());
    }

    @Test
    void 预演模式不受额度闸限制() {
        when(entitlementService.remainingToday("apply:liepin", "max_daily_apply")).thenReturn(0);
        LiepinProperties.LiepinConfig config = liepinProperties.get();
        config.setDryRun(true);

        assertNull(liepinService().start());
        verify(browserManager).submitAsync(any());
    }

    @Test
    void AI不可用又没有固定打招呼语时不允许真投Boss() {
        bossProperties.get().setSayHi("");

        String error = bossService().start();

        assertTrue(error.contains("打招呼语"), error);
        verify(browserManager, never()).submitAsync(any());
    }

    // ------------------------------------------------------------------
    // 每小时节流
    // ------------------------------------------------------------------

    /**
     * 日额度抬到 300 之后，风控看的是密度不是总量。这里把小时桶灌满，断言真投被闸住、
     * 任务转入停止，并且日志把"为什么停"和"什么时候能接着投"说清楚。
     */
    @Test
    void 本小时投满了就收工不再猛发() {
        when(entitlementService.usedToday(startsWith("apply:liepin:2026"))).thenReturn(999);
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        LiepinService service = liepinService();

        service.processCardForTest(card(), "陈列设计", liepinProperties.get(), null);

        verify(liepinDriver, never()).deliver(any(), any(), any(), any(), any(), any());
        assertTrue(service.status().getLogs().stream().anyMatch(l -> l.contains("本小时已投满 40 个")),
                service.status().getLogs().toString());
    }

    /** 预演不发任何东西到平台，不该被小时节流拖住 */
    @Test
    void 预演不受小时节流影响() {
        when(entitlementService.usedToday(startsWith("apply:liepin:2026"))).thenReturn(999);
        LiepinProperties.LiepinConfig config = liepinProperties.get();
        config.setDryRun(true);
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(liepinDriver.deliver(any(), any(), any(), any(), any(), any()))
                .thenReturn(DeliveryOutcome.preview("预演"));

        liepinService().processCardForTest(card(), "陈列设计", config, null);

        verify(liepinDriver).deliver(any(), any(), any(), any(), any(), any());
    }

    /** 一次真投要同时记进日额度桶和本小时桶，少记一个闸就形同虚设 */
    @Test
    void 真投成功同时记日额度和小时两个桶() {
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(liepinDriver.deliver(any(), any(), any(), any(), any(), any()))
                .thenReturn(DeliveryOutcome.delivered("已沟通"));

        liepinService().processCardForTest(card(), "陈列设计", liepinProperties.get(), null);

        verify(entitlementService).recordUse("apply:liepin");
        verify(entitlementService).recordUse(startsWith("apply:liepin:2026"));
    }

    @Test
    void 预演模式不检查打招呼语() {
        BossProperties.BossConfig config = bossProperties.get();
        config.setSayHi("");
        config.setDryRun(true);

        assertNull(bossService().start());
    }

    /** 猎聘/51job/智联/实习僧不自己发话术，sayHi 空着也照样能投 */
    @Test
    void 不发话术的平台不因sayHi为空被拦住() {
        liepinProperties.get().setSayHi("");

        assertNull(liepinService().start());
    }
}
