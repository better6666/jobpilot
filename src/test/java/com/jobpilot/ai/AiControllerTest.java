package com.jobpilot.ai;

import com.jobpilot.license.LicenseClient;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 配置接口测试。
 *
 * 最关键的一条：GET 绝不能回传明文 apiKey。管理页是局域网内也能打开的
 * 本地服务，回显明文等于把 key 广播给同网段的人。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiControllerTest {

    @Mock
    private ConfigService configService;
    @Mock
    private AiService aiService;
    @Mock
    private LicenseService licenseService;
    @Mock
    private LicenseClient licenseClient;

    private final LicenseProperties licenseProperties = new LicenseProperties();

    private MockMvc mockMvc;
    private AiProperties properties;
    private AiConfig stored;

    @BeforeEach
    void setUp() {
        properties = new AiProperties(configService);
        licenseProperties.setApiBase("https://license.example.com");
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AiController(properties, aiService, licenseService, licenseProperties, licenseClient)).build();
        stored = new AiConfig();
        stored.setEnabled(true);
        stored.setBaseUrl("https://relay.example.com/v1");
        stored.setApiKey("sk-relay-abcdef1234567890");
        stored.setModel("deepseek-chat");
        stored.setPersona("服装陈列设计专业");
        stored.setTemperature(0.8);
        // 让 mock 的配置表真的能存能取：setJson 之后 getJson 要看到新值，
        // 否则保存接口回显的永远是旧配置，断言就失去意义
        org.mockito.Mockito.doAnswer(inv -> {
            stored = inv.getArgument(1);
            return null;
        }).when(configService).setJson(anyString(), any());
        org.mockito.Mockito.doAnswer(inv -> stored).when(configService)
                .getJson(eq(AiProperties.CONFIG_KEY), eq(AiConfig.class), any(AiConfig.class));
    }

    @Test
    void 读取配置时apiKey是打码的() throws Exception {
        mockMvc.perform(get("/api/ai/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.baseUrl").value("https://relay.example.com/v1"))
                .andExpect(jsonPath("$.data.apiKeySet").value(true))
                .andExpect(jsonPath("$.data.model").value("deepseek-chat"))
                .andExpect(jsonPath("$.data.persona").value("服装陈列设计专业"))
                .andExpect(jsonPath("$.data.temperature").value(0.8))
                .andExpect(jsonPath("$.data.apiKeyMasked").value("sk-rela…7890"));

        // 整个响应体里都不允许出现明文
        String body = mockMvc.perform(get("/api/ai/config")).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("sk-relay-abcdef1234567890"), "响应体泄露了明文 key");
        assertFalse(body.contains("abcdef1234567890"), "响应体泄露了 key 主体");
    }

    @Test
    void 没配key时apiKeySet为false且打码值为空() throws Exception {
        stored.setApiKey("");
        mockMvc.perform(get("/api/ai/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeySet").value(false))
                .andExpect(jsonPath("$.data.apiKeyMasked").value(""));
    }

    @Test
    void 保存配置时不带apiKey就保留原来的() throws Exception {
        mockMvc.perform(put("/api/ai/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"baseUrl\":\"https://api.deepseek.com/v1\",\"model\":\"deepseek-chat\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeySet").value(true));

        ArgumentCaptor<AiConfig> captor = ArgumentCaptor.forClass(AiConfig.class);
        verify(configService).setJson(eq("ai"), captor.capture());
        assertEquals("sk-relay-abcdef1234567890", captor.getValue().getApiKey(), "没传 key 不能把已配的清掉");
    }

    @Test
    void 保存配置时传空串就是清除key() throws Exception {
        mockMvc.perform(put("/api/ai/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"apiKey\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeySet").value(false));

        ArgumentCaptor<AiConfig> captor = ArgumentCaptor.forClass(AiConfig.class);
        verify(configService).setJson(eq("ai"), captor.capture());
        assertEquals("", captor.getValue().getApiKey());
    }

    @Test
    void 测试连接用请求体里的覆盖值() throws Exception {
        doReturn(AiService.AiResult.ok("我能正常工作")).when(aiService)
                .chat(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.3));

        mockMvc.perform(post("/api/ai/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"https://api.openai.com\",\"apiKey\":\"sk-temp-123456\",\"model\":\"gpt-4o-mini\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.reply").value("我能正常工作"))
                .andExpect(jsonPath("$.data.baseUrl").value("https://api.openai.com/v1"))
                .andExpect(jsonPath("$.data.model").value("gpt-4o-mini"));

        verify(aiService).chat("https://api.openai.com", "sk-temp-123456", "gpt-4o-mini",
                "你是连通性测试助手。", "请用一句中文回答：你能正常工作吗？", 0.3);
    }

    @Test
    void 测试连接不带请求体时用已保存配置() throws Exception {
        doReturn(AiService.AiResult.ok("可以")).when(aiService)
                .chat(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.3));

        mockMvc.perform(post("/api/ai/test").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.baseUrl").value("https://relay.example.com/v1"));

        verify(aiService).chat("https://relay.example.com/v1", "sk-relay-abcdef1234567890", "deepseek-chat",
                "你是连通性测试助手。", "请用一句中文回答：你能正常工作吗？", 0.3);
    }

    @Test
    void 测试连接失败时返回原因不抛异常() throws Exception {
        doReturn(AiService.AiResult.fail("接口拒绝了（key 无效或没有该模型权限）")).when(aiService)
                .chat(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.3));

        mockMvc.perform(post("/api/ai/test").content("{}").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.error").value("接口拒绝了（key 无效或没有该模型权限）"));
    }

    @Test
    void 拉取模型列表用覆盖地址和key() throws Exception {
        doReturn(AiService.ModelResult.ok(List.of("deepseek-chat", "gpt-4o-mini"))).when(aiService)
                .models(anyString(), anyString());

        mockMvc.perform(get("/api/ai/models")
                        .param("baseUrl", "https://relay.example.com/v1")
                        .param("apiKey", "sk-relay-abcdef1234567890"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.baseUrl").value("https://relay.example.com/v1"))
                .andExpect(jsonPath("$.data.models[0]").value("deepseek-chat"))
                .andExpect(jsonPath("$.data.models[1]").value("gpt-4o-mini"));

        verify(aiService).models("https://relay.example.com/v1", "sk-relay-abcdef1234567890");
    }

    @Test
    void 拉取模型列表不带参数时用已保存配置() throws Exception {
        doReturn(AiService.ModelResult.ok(List.of("m"))).when(aiService).models(anyString(), anyString());

        mockMvc.perform(get("/api/ai/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true));

        verify(aiService).models("https://relay.example.com/v1", "sk-relay-abcdef1234567890");
    }

    @Test
    void 拉取模型列表失败时带回原因() throws Exception {
        doReturn(AiService.ModelResult.fail("API Key 没配")).when(aiService).models(anyString(), anyString());

        mockMvc.perform(get("/api/ai/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.error").value("API Key 没配"));
    }

    // ------------------------------------------------------------------
    // 平台模式
    // ------------------------------------------------------------------

    /** 切成平台模式：接口三要素整个不用填 */
    private void switchToPlatform() {
        stored.setMode(AiProperties.MODE_PLATFORM);
        stored.setBaseUrl("");
        stored.setApiKey(null);
        stored.setModel("");
    }

    @Test
    void 平台模式下不要求接口三要素() throws Exception {
        switchToPlatform();

        mockMvc.perform(get("/api/ai/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("platform"))
                .andExpect(jsonPath("$.data.apiKeySet").value(false))
                // licenseClient 默认返回 null = 服务端没答上，按不可用处理但不报错
                .andExpect(jsonPath("$.data.platformAvailable").value(false))
                .andExpect(jsonPath("$.data.platformMessage").value("连不上卡密服务端，平台话术暂时不可用"));
    }

    @Test
    void 平台中转可用时回传模型名() throws Exception {
        switchToPlatform();
        doReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"success\":true,\"data\":{\"configured\":true,\"model\":\"step-5-preview\"}}"))
                .when(licenseClient).get(anyString(), eq("/api/ai/info"));

        mockMvc.perform(get("/api/ai/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.platformAvailable").value(true))
                .andExpect(jsonPath("$.data.platformModel").value("step-5-preview"));

        // 平台用的什么模型可以说，平台那把 key 一个字都不能过来
        String body = mockMvc.perform(get("/api/ai/config")).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("api_key"), body);
        assertFalse(body.toLowerCase().contains("sk-"), body);
    }

    @Test
    void 平台没开中转时提示改用自有接口() throws Exception {
        switchToPlatform();
        doReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"success\":true,\"data\":{\"configured\":false,\"model\":null}}"))
                .when(licenseClient).get(anyString(), eq("/api/ai/info"));

        mockMvc.perform(get("/api/ai/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.platformAvailable").value(false))
                .andExpect(jsonPath("$.data.platformMessage").value("平台没开 AI 中转，可改用「我自己的接口」"));
    }

    @Test
    void 平台模式测试连接走服务端代理() throws Exception {
        switchToPlatform();
        doReturn(Map.of("token", "tok-1", "device_id", "dev-1")).when(licenseService).proxyCredentials();
        doReturn(AiService.AiResult.ok("我能正常工作")).when(aiService)
                .chatPlatform(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.3));

        mockMvc.perform(post("/api/ai/test").content("{}").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.mode").value("platform"))
                .andExpect(jsonPath("$.data.reply").value("我能正常工作"));

        verify(aiService).chatPlatform("https://license.example.com", "tok-1", "dev-1",
                "你是连通性测试助手。", "请用一句中文回答：你能正常工作吗？", 0.3);
        // 不能顺手去直连——那会把请求发到客户没配的地址上
        org.mockito.Mockito.verify(aiService, org.mockito.Mockito.never())
                .chat(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.3));
    }

    @Test
    void 平台模式卡密没激活时测试连接说清楚() throws Exception {
        switchToPlatform();
        doReturn(null).when(licenseService).proxyCredentials();

        mockMvc.perform(post("/api/ai/test").content("{}").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.error").value("卡密未激活，先激活才能用平台话术"));
    }

    @Test
    void 平台模式下不提供拉取模型() throws Exception {
        switchToPlatform();

        mockMvc.perform(get("/api/ai/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.error").value("模型由平台统一指定，不能自己拉取"));

        org.mockito.Mockito.verify(aiService, org.mockito.Mockito.never()).models(anyString(), anyString());
    }

    @Test
    void 请求体里显式选自有接口时按自有接口测() throws Exception {
        switchToPlatform();
        doReturn(AiService.AiResult.ok("可以")).when(aiService)
                .chat(anyString(), anyString(), anyString(), anyString(), anyString(), eq(0.3));

        mockMvc.perform(post("/api/ai/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"custom\",\"baseUrl\":\"https://api.openai.com\",\"apiKey\":\"sk-temp-123456\",\"model\":\"gpt-4o-mini\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("custom"))
                .andExpect(jsonPath("$.data.baseUrl").value("https://api.openai.com/v1"));

        verify(aiService).chat("https://api.openai.com", "sk-temp-123456", "gpt-4o-mini",
                "你是连通性测试助手。", "请用一句中文回答：你能正常工作吗？", 0.3);
    }
}
