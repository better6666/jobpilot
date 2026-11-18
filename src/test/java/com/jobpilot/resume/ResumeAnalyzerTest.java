package com.jobpilot.resume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.ai.*;
import com.jobpilot.license.LicenseProperties;
import com.jobpilot.license.LicenseService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResumeAnalyzerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiProperties properties = mock(AiProperties.class);
    private final AiService ai = mock(AiService.class);
    private final LicenseService license = mock(LicenseService.class);
    private final LicenseProperties licenseProperties = new LicenseProperties();
    private final ResumeAnalyzer analyzer = new ResumeAnalyzer(mapper, properties, ai, license, licenseProperties);

    @Test void realHttpTransportReturnsTheCompleteStructuredProfile() throws Exception {
        var provider = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var request = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        String report = mapper.writeValueAsString(ResumeServiceTest.profile());
        provider.createContext("/v1/chat/completions", exchange -> {
            request.set(mapper.readTree(exchange.getRequestBody()));
            byte[] body = mapper.writeValueAsBytes(Map.of("choices", java.util.List.of(Map.of("message", Map.of("content", report)))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        provider.start();
        try {
            AiConfig config = new AiConfig(); config.setMode("custom"); config.setModel("qa-model");
            config.setBaseUrl("http://127.0.0.1:" + provider.getAddress().getPort() + "/v1"); config.setApiKey("qa-only");
            when(properties.get()).thenReturn(config);
            var real = new ResumeAnalyzer(mapper, properties, new AiService(mapper), license, licenseProperties);
            assertThat(real.analyze("Java 订单接口项目，13800138000", "Java 后端")).isEqualTo(ResumeServiceTest.profile());
            assertThat(request.get().path("model").asText()).isEqualTo("qa-model");
            assertThat(request.get().path("messages").get(1).path("content").asText()).contains("[电话已隐藏]").doesNotContain("13800138000");
            assertThat(report.length()).isGreaterThan(120);
        } finally { provider.stop(0); }
    }

    @Test void customModeReusesSavedConnectionAndRedactsContacts() throws Exception {
        AiConfig config = new AiConfig(); config.setMode("custom"); config.setBaseUrl("https://relay.example/v1");
        config.setApiKey("fixture-key"); config.setModel("fixture-model"); when(properties.get()).thenReturn(config);
        when(ai.chat(anyString(),anyString(),anyString(),anyString(),anyString(),anyDouble()))
                .thenReturn(AiService.AiResult.ok(mapper.writeValueAsString(ResumeServiceTest.profile())));
        var profile = analyzer.analyze("Java 开发，邮箱 candidate@example.com，电话 13800138000，证件 12345678901234567X", "Java 开发");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).chat(eq(config.getBaseUrl()),eq("fixture-key"),eq("fixture-model"),anyString(),prompt.capture(),eq(0.3));
        assertThat(prompt.getValue()).contains("Java 开发", "[邮箱已隐藏]", "[电话已隐藏]", "[证件号已隐藏]")
                .doesNotContain("candidate@example.com", "13800138000", "12345678901234567X");
        assertThat(profile.persona()).isEqualTo(ResumeServiceTest.profile().persona());
        verifyNoInteractions(license);
    }

    @Test void platformModeUsesExistingProxyCredentialsAndKeepsFullReport() throws Exception {
        AiConfig config = new AiConfig(); config.setMode("platform"); config.setModel("chosen-model");
        when(properties.get()).thenReturn(config); when(license.proxyCredentials()).thenReturn(Map.of("token","fixture-token","device_id","fixture-device"));
        licenseProperties.setApiBase("https://platform.example");
        when(ai.chatPlatform(anyString(),anyString(),anyString(),anyString(),anyString(),anyDouble(),anyString()))
                .thenReturn(AiService.AiResult.ok("```json\n"+mapper.writeValueAsString(ResumeServiceTest.profile())+"\n```"));
        assertThat(analyzer.analyze("Java 项目经历", "").improvements()).hasSize(1);
        verify(ai).chatPlatform(eq("https://platform.example"),eq("fixture-token"),eq("fixture-device"),anyString(),anyString(),eq(0.3),eq("chosen-model"));
        verify(ai,never()).chat(anyString(),anyString(),anyString(),anyString(),anyString(),anyDouble());
    }

    @Test void invalidOrUnavailableAiNeverProducesAFakeProfile() {
        AiConfig config=new AiConfig();config.setMode("custom");when(properties.get()).thenReturn(config);
        when(ai.chat(any(),any(),any(),any(),any(),anyDouble())).thenReturn(AiService.AiResult.fail("连接超时"));
        assertThatThrownBy(()->analyzer.analyze("简历内容", "")).hasMessageContaining("连接超时");
        assertThatThrownBy(()->analyzer.parse("模型未返回 JSON")).hasMessageContaining("格式不完整");
        assertThatThrownBy(()->analyzer.parse("{\"summary\":\"只有一句话\"}")).hasMessageContaining("格式不完整");
    }
}
