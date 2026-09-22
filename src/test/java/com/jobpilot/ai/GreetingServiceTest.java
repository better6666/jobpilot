package com.jobpilot.ai;

import com.jobpilot.delivery.Delivery;
import com.jobpilot.delivery.DeliveryMapper;
import com.jobpilot.delivery.JobCard;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 话术编排测试：什么时候走 AI、什么时候退回固定话术、重复了怎么办。
 *
 * 用真 AiService 里跑不通的网络层被 mock 掉，其余（配置读取、提示词拼装、
 * 去重、兜底判断）全是真代码——这些才是容易出错的地方。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GreetingServiceTest {

    @Mock
    private com.jobpilot.system.ConfigService configService;
    @Mock
    private AiService aiService;
    @Mock
    private DeliveryMapper deliveryMapper;
    @Mock
    private LicenseService licenseService;

    private final LicenseProperties licenseProperties = new LicenseProperties();

    private GreetingService service;
    private AiConfig cfg;

    @BeforeEach
    void setUp() {
        cfg = new AiConfig();
        doReturn(cfg).when(configService).getJson(anyString(), eq(AiConfig.class), any(AiConfig.class));
        licenseProperties.setApiBase("https://license.example.com");
        service = new GreetingService(new AiProperties(configService), aiService, deliveryMapper,
                licenseService, licenseProperties,
                new com.jobpilot.license.EntitlementService(licenseService, null, licenseProperties,
                        new com.fasterxml.jackson.databind.ObjectMapper(), null));
    }

    private void fullyConfigured() {
        cfg.setEnabled(true);
        cfg.setBaseUrl("https://relay.example.com/v1");
        cfg.setApiKey("sk-relay-123456");
        cfg.setModel("deepseek-chat");
        cfg.setPersona("服装陈列设计专业，做过橱窗陈列实习");
        cfg.setTemperature(0.7);
    }

    /**
     * 平台模式：客户只填人设。接口/key/模型整个不用管，
     * 由卡密服务端代理——这也是买卡客户拿不到平台 key 的原因。
     */
    private void platformConfigured() {
        cfg.setEnabled(true);
        cfg.setMode(AiProperties.MODE_PLATFORM);
        cfg.setPersona("服装陈列设计专业，做过橱窗陈列实习");
        cfg.setTemperature(0.7);
        when(licenseService.proxyCredentials())
                .thenReturn(Map.of("token", "tok-123", "device_id", "dev-456"));
    }

    private void recentGreetings(String... greetings) {
        List<Delivery> rows = new java.util.ArrayList<>();
        long id = greetings.length;
        for (String g : greetings) {
            Delivery d = new Delivery();
            d.setId(id--);
            d.setGreeting(g);
            rows.add(d);
        }
        doReturn(rows).when(deliveryMapper).selectList(any());
    }

    private static JobCard card() {
        return new JobCard() {
            @Override
            public String getJobId() {
                return "job-1";
            }

            @Override
            public String getBossId() {
                return "boss-1";
            }

            @Override
            public String getJobName() {
                return "陈列设计助理";
            }

            @Override
            public String getBrandName() {
                return "某某服饰";
            }

            @Override
            public String getSalaryDesc() {
                return "8-12K";
            }

            @Override
            public String getCityName() {
                return "苏州";
            }

            @Override
            public String getAreaDistrict() {
                return "吴中区";
            }

            @Override
            public String getJobExperience() {
                return "1-3年";
            }

            @Override
            public String getJobDegree() {
                return "大专";
            }

            @Override
            public String getBossName() {
                return "王女士";
            }

            @Override
            public String getBossTitle() {
                return "HR";
            }

            @Override
            public String getBossActiveTimeDesc() {
                return "3日内活跃";
            }

            @Override
            public String getJobUrl() {
                return "https://example.com/job/1";
            }

            @Override
            public String getPostDescription() {
                return "负责门店橱窗陈列与换季调整";
            }

            @Override
            public String getIndustryName() {
                return "服装纺织";
            }

            @Override
            public String getBrandScaleName() {
                return "100-499人";
            }
        };
    }

    // ------------------------------------------------------------------
    // 兜底
    // ------------------------------------------------------------------

    @Test
    void AI关着时用固定话术且不发起请求() {
        GreetingService.Greeting g = service.compose(card(), "您好，看到贵司在招人");
        assertEquals("您好，看到贵司在招人", g.text());
        assertNull(g.note(), "本来就用固定话术，不需要解释");
        verifyNoInteractions(aiService);
    }

    @Test
    void 没填求职者背景时退回固定话术并说明原因() {
        cfg.setEnabled(true);
        cfg.setBaseUrl("https://relay.example.com/v1");
        cfg.setApiKey("sk-x");
        cfg.setModel("m");

        GreetingService.Greeting g = service.compose(card(), "固定话术");

        assertEquals("固定话术", g.text());
        assertTrue(g.note().contains("没填求职者背景"), g.note());
        verifyNoInteractions(aiService);
    }

    @Test
    void 接口三要素没配全时退回固定话术并说明原因() {
        cfg.setEnabled(true);
        cfg.setPersona("服装陈列设计专业");
        cfg.setBaseUrl("https://relay.example.com/v1");
        cfg.setApiKey("sk-x");
        // model 空着

        GreetingService.Greeting g = service.compose(card(), "固定话术");

        assertEquals("固定话术", g.text());
        assertTrue(g.note().contains("没配全"), g.note());
        verifyNoInteractions(aiService);
    }

    @Test
    void 接口报错时退回固定话术并带上原因() {
        fullyConfigured();
        recentGreetings();
        doReturn(AiService.AiResult.fail("接口拒绝了（key 无效或没有该模型权限）"))
                .when(aiService).chat(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.7));

        GreetingService.Greeting g = service.compose(card(), "固定话术");

        assertEquals("固定话术", g.text());
        assertTrue(g.note().contains("key 无效"), g.note());
    }

    @Test
    void 模型输出为空时退回固定话术() {
        fullyConfigured();
        recentGreetings();
        doReturn(AiService.AiResult.ok("   ")).when(aiService)
                .chat(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.7));

        GreetingService.Greeting g = service.compose(card(), "固定话术");

        assertEquals("固定话术", g.text());
        assertTrue(g.note().contains("模型输出为空"), g.note());
    }

    // ------------------------------------------------------------------
    // 正常生成
    // ------------------------------------------------------------------

    @Test
    void 生成成功时用模型输出并清洗() {
        fullyConfigured();
        recentGreetings();
        doReturn(AiService.AiResult.ok("```text\n话术：您好，看到贵司在招陈列设计助理，我做过橱窗陈列实习。\n```"))
                .when(aiService).chat(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.7));

        GreetingService.Greeting g = service.compose(card(), "固定话术");

        assertEquals("您好，看到贵司在招陈列设计助理，我做过橱窗陈列实习。", g.text());
        assertNull(g.note());
    }

    @Test
    void 生成请求用的是配置里的地址key模型和温度() {
        fullyConfigured();
        recentGreetings();
        doReturn(AiService.AiResult.ok("您好")).when(aiService)
                .chat(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.7));

        service.compose(card(), "固定话术");

        org.mockito.ArgumentCaptor<String> system = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(aiService).chat(eq("https://relay.example.com/v1"), eq("sk-relay-123456"),
                eq("deepseek-chat"), system.capture(), user.capture(), eq(0.7));
        // 人设在系统提示词里，不在用户提示词里
        assertTrue(system.getValue().contains("服装陈列设计专业，做过橱窗陈列实习"), system.getValue());
        assertFalse(user.getValue().contains("服装陈列设计专业，做过橱窗陈列实习"));
    }

    @Test
    void 提示词里带上了岗位字段和风格要求() {
        fullyConfigured();
        recentGreetings();
        doReturn(AiService.AiResult.ok("您好")).when(aiService)
                .chat(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.7));

        service.compose(card(), "固定话术");

        org.mockito.ArgumentCaptor<String> user =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(aiService).chat(anyString(), anyString(), anyString(), anyString(),
                user.capture(), eq(0.7));
        String prompt = user.getValue();
        assertTrue(prompt.contains("【岗位信息】"), prompt);
        assertTrue(prompt.contains("岗位：陈列设计助理"), prompt);
        assertTrue(prompt.contains("公司：某某服饰"), prompt);
        assertTrue(prompt.contains("薪资：8-12K"), prompt);
        assertTrue(prompt.contains("地点：苏州"), prompt);
        assertTrue(prompt.contains("经验要求：1-3年"), prompt);
        assertTrue(prompt.contains("学历要求：大专"), prompt);
        assertTrue(prompt.contains("职位描述：负责门店橱窗陈列与换季调整"), prompt);
        assertTrue(prompt.contains("【本次要求】"), prompt);
    }

    @Test
    void JD太长时只带前800字() {
        fullyConfigured();
        recentGreetings();
        doReturn(AiService.AiResult.ok("您好")).when(aiService)
                .chat(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.7));
        JobCard longCard = new JobCard() {
            @Override
            public String getJobId() {
                return "j";
            }

            @Override
            public String getBossId() {
                return null;
            }

            @Override
            public String getJobName() {
                return "陈列设计助理";
            }

            @Override
            public String getBrandName() {
                return "某某服饰";
            }

            @Override
            public String getSalaryDesc() {
                return null;
            }

            @Override
            public String getCityName() {
                return null;
            }

            @Override
            public String getAreaDistrict() {
                return null;
            }

            @Override
            public String getJobExperience() {
                return null;
            }

            @Override
            public String getJobDegree() {
                return null;
            }

            @Override
            public String getBossName() {
                return null;
            }

            @Override
            public String getBossTitle() {
                return null;
            }

            @Override
            public String getJobUrl() {
                return null;
            }

            @Override
            public String getPostDescription() {
                return "岗".repeat(5000);
            }

            @Override
            public String getIndustryName() {
                return null;
            }

            @Override
            public String getBrandScaleName() {
                return null;
            }

            @Override
            public String getBossActiveTimeDesc() {
                return null;
            }
        };

        service.compose(longCard, "固定话术");

        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(aiService).chat(anyString(), anyString(), anyString(), anyString(),
                user.capture(), eq(0.7));
        assertTrue(user.getValue().contains("…"), user.getValue());
        assertFalse(user.getValue().contains("岗".repeat(900)), "JD 没截断，提示词会过长");
    }

    // ------------------------------------------------------------------
    // 去重
    // ------------------------------------------------------------------

    @Test
    void 和最近投递重复时换一种说法重来一次() {
        fullyConfigured();
        recentGreetings("您好，看到贵司在招陈列设计，想聊聊");
        String used = "您好，看到贵司在招陈列设计，想聊聊";
        doReturn(AiService.AiResult.ok(used), AiService.AiResult.ok("贵司这个岗位看重换季陈列，我正好实习时整过两季橱窗"))
                .when(aiService).chat(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.7));

        GreetingService.Greeting g = service.compose(card(), "固定话术");

        assertEquals("贵司这个岗位看重换季陈列，我正好实习时整过两季橱窗", g.text());
        assertNull(g.note());
        org.mockito.Mockito.verify(aiService, org.mockito.Mockito.times(2))
                .chat(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.7));
    }

    @Test
    void 重试时把上一句带给模型要求换切入点() {
        fullyConfigured();
        String used = "您好，看到贵司在招陈列设计，想聊聊";
        recentGreetings(used);
        doReturn(AiService.AiResult.ok(used), AiService.AiResult.ok("换了个完全不同的说法"))
                .when(aiService).chat(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.7));

        service.compose(card(), "固定话术");

        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(aiService, org.mockito.Mockito.times(2))
                .chat(anyString(), anyString(), anyString(), anyString(), user.capture(), eq(0.7));
        assertFalse(user.getAllValues().get(0).contains("上一句已经写过"));
        assertTrue(user.getAllValues().get(1).contains("上一句已经写过「" + used + "」"),
                user.getAllValues().get(1));
    }

    @Test
    void 重试后还是重复就退回固定话术() {
        fullyConfigured();
        String used = "您好，看到贵司在招陈列设计，想聊聊";
        recentGreetings(used);
        doReturn(AiService.AiResult.ok(used), AiService.AiResult.ok(used))
                .when(aiService).chat(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.7));

        GreetingService.Greeting g = service.compose(card(), "固定话术");

        assertEquals("固定话术", g.text());
        assertTrue(g.note().contains("重复"), g.note());
    }

    @Test
    void 读不到历史话术时不阻塞生成() {
        fullyConfigured();
        // selectList 抛异常（比如库里没这张表）
        doReturn(null).when(deliveryMapper).selectList(any());
        doReturn(AiService.AiResult.ok("您好，一句新话术")).when(aiService)
                .chat(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.7));

        GreetingService.Greeting g = service.compose(card(), "固定话术");

        assertEquals("您好，一句新话术", g.text());
    }

    @Test
    void 标点空白不计入重复判定() {
        assertTrue(GreetingService.isDuplicate("您好，想聊聊这个岗位！", List.of("您好想聊聊这个岗位")));
        assertTrue(GreetingService.isDuplicate("您好 想聊聊", List.of("您好，想聊聊。")));
        assertFalse(GreetingService.isDuplicate("您好，想聊聊这个岗位", List.of("您好，想聊聊那个岗位")));
        assertFalse(GreetingService.isDuplicate("您好", List.of()));
        assertFalse(GreetingService.isDuplicate(null, List.of("您好")));
        assertEquals("您好想聊聊", GreetingService.normalize("您好，想聊聊。"));
    }

    // ------------------------------------------------------------------
    // 猎聘追问
    // ------------------------------------------------------------------

    @Test
    void 追问在AI关着时直接用固定话术() {
        GreetingService.Greeting g = service.composeFollowUp(card(), "方便看下我的简历吗");
        assertEquals("方便看下我的简历吗", g.text());
        assertNull(g.note());
        verifyNoInteractions(aiService);
    }

    @Test
    void 追问超长时截到60字() {
        fullyConfigured();
        recentGreetings();
        doReturn(AiService.AiResult.ok("这是一句特别长的追问".repeat(10))).when(aiService)
                .chat(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.7));

        GreetingService.Greeting g = service.composeFollowUp(card(), "固定话术");

        assertEquals(60, g.text().length());
    }

    @Test
    void 追问生成失败时不带原因静默退回() {
        fullyConfigured();
        recentGreetings();
        doReturn(AiService.AiResult.fail("接口限流")).when(aiService)
                .chat(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.7));

        GreetingService.Greeting g = service.composeFollowUp(card(), "固定话术");

        assertEquals("固定话术", g.text());
        assertNull(g.note(), "追问是锦上添花，失败不值得在日志里占一行");
    }

    // ------------------------------------------------------------------
    // 平台模式（接口/key 都在服务端，客户只填人设）
    // ------------------------------------------------------------------

    @Test
    void 平台模式下不填接口也能生成() {
        platformConfigured();
        recentGreetings();
        doReturn(AiService.AiResult.ok("您好，看到贵司在招陈列设计助理，我做过两季橱窗陈列")).when(aiService)
                .chatPlatform(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.7));

        GreetingService.Greeting g = service.compose(card(), "固定话术");

        assertEquals("您好，看到贵司在招陈列设计助理，我做过两季橱窗陈列", g.text());
        assertNull(g.note());
    }

    @Test
    void 平台模式走服务端代理并带上卡密凭证() {
        platformConfigured();
        recentGreetings();
        doReturn(AiService.AiResult.ok("您好")).when(aiService)
                .chatPlatform(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.7));

        service.compose(card(), "固定话术");

        verify(aiService).chatPlatform(eq("https://license.example.com"), eq("tok-123"), eq("dev-456"),
                anyString(), anyString(), eq(0.7));
        // 平台模式下绝不能去直连某个接口——那等于要客户自己承担 key
        org.mockito.Mockito.verify(aiService, org.mockito.Mockito.never())
                .chat(anyString(), anyString(), anyString(), anyString(), anyString(), anyDouble());
    }

    @Test
    void 平台模式下卡密没激活就退回固定话术并说明原因() {
        cfg.setEnabled(true);
        cfg.setMode(AiProperties.MODE_PLATFORM);
        cfg.setPersona("服装陈列设计专业");
        // 没激活：拿不到 token
        when(licenseService.proxyCredentials()).thenReturn(null);

        GreetingService.Greeting g = service.compose(card(), "固定话术");

        assertEquals("固定话术", g.text());
        assertTrue(g.note().contains("卡密未激活"), g.note());
    }

    @Test
    void 平台模式接口报错时退回固定话术并带上原因() {
        platformConfigured();
        recentGreetings();
        doReturn(AiService.AiResult.fail("平台没配 AI 中转")).when(aiService)
                .chatPlatform(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.7));

        GreetingService.Greeting g = service.compose(card(), "固定话术");

        assertEquals("固定话术", g.text());
        assertTrue(g.note().contains("平台没配 AI 中转"), g.note());
    }

    @Test
    void 平台模式下不填接口不要素拦客户() {
        // 只有人设，接口三要素全空——这是平台模式的正常形态，不该被"没配全"拦下
        platformConfigured();
        recentGreetings();
        doReturn(AiService.AiResult.ok("您好")).when(aiService)
                .chatPlatform(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.7));

        GreetingService.Greeting g = service.compose(card(), "固定话术");

        assertEquals("您好", g.text());
        assertNull(g.note());
    }

    @Test
    void 平台模式下追问也走服务端代理() {
        platformConfigured();
        recentGreetings();
        doReturn(AiService.AiResult.ok("方便看下我的简历吗")).when(aiService)
                .chatPlatform(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.7));

        GreetingService.Greeting g = service.composeFollowUp(card(), "固定追问");

        assertEquals("方便看下我的简历吗", g.text());
        verify(aiService).chatPlatform(eq("https://license.example.com"), eq("tok-123"), eq("dev-456"),
                anyString(), anyString(), eq(0.7));
    }

    // ------------------------------------------------------------------
    // 状态描述
    // ------------------------------------------------------------------

    @Test
    void 状态描述区分关闭半配置和就绪() {
        assertNull(service.describe());

        cfg.setEnabled(true);
        assertTrue(service.describe().contains("没填求职者背景"));

        // 平台模式：只填人设就绪，接口三要素整个不用管
        cfg.setMode(AiProperties.MODE_PLATFORM);
        cfg.setPersona("服装陈列设计专业");
        assertEquals("AI 话术已启用（平台提供），生成失败会自动退回固定话术", service.describe());

        // 自有接口模式：三要素没齐才说没配全
        cfg.setMode(AiProperties.MODE_CUSTOM);
        assertTrue(service.describe().contains("没配全"));

        cfg.setBaseUrl("https://relay.example.com/v1");
        cfg.setApiKey("sk-x");
        cfg.setModel("deepseek-chat");
        assertEquals("AI 话术已启用（自有接口 deepseek-chat），生成失败会自动退回固定话术",
                service.describe());
    }
}
