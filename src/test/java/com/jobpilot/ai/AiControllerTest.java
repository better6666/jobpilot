package com.jobpilot.ai;

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

    private MockMvc mockMvc;
    private AiProperties properties;
    private AiConfig stored;

    @BeforeEach
    void setUp() {
        properties = new AiProperties(configService);
        mockMvc = MockMvcBuilders.standaloneSetup(new AiController(properties, aiService)).build();
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
}
