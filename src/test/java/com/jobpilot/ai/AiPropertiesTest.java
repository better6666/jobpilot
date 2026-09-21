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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

/**
 * AI 配置存取测试。重点是 apiKey 的写入规则——管理页的输入框留空表示
 * "我没动 key"，不能因为没传就把用户配好的 key 清掉。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiPropertiesTest {

    @Mock
    private ConfigService configService;

    private AiProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AiProperties(configService);
    }

    private void stored(AiConfig value) {
        doReturn(value).when(configService).getJson(eq(AiProperties.CONFIG_KEY), eq(AiConfig.class), any(AiConfig.class));
    }

    @Test
    void 没存过配置时给一份默认值() {
        stored(new AiConfig());
        AiConfig cfg = properties.get();

        assertFalse(cfg.isEnabled(), "默认必须关着：用户没配过就发 AI 话术会很难看");
        assertEquals("", cfg.getBaseUrl());
        // apiKey 默认是 null 而不是空串：null 表示"这次没提这个字段"，
        // 保存时要按原值保留，空串才是用户显式清除
        assertNull(cfg.getApiKey());
        assertEquals("", cfg.getModel());
        assertEquals("", cfg.getPersona());
        assertEquals(0.9, cfg.getTemperature(), 1e-9);
    }

    @Test
    void 不传apiKey时保留原值() {
        stored(savedWithKey("sk-keep-123456"));
        AiConfig incoming = new AiConfig();
        incoming.setEnabled(true);
        incoming.setBaseUrl("https://api.deepseek.com/v1");
        incoming.setModel("deepseek-chat");
        // apiKey 故意不设，模拟管理页没动这个输入框

        properties.save(incoming);

        assertEquals("sk-keep-123456", incoming.getApiKey());
        verify(configService).setJson(eq(AiProperties.CONFIG_KEY), any(AiConfig.class));
    }

    @Test
    void 传空串时清除apiKey() {
        stored(savedWithKey("sk-old-123456"));
        AiConfig incoming = new AiConfig();
        incoming.setEnabled(true);
        incoming.setApiKey("");

        properties.save(incoming);

        assertEquals("", incoming.getApiKey());
    }

    @Test
    void 传了新值就换掉() {
        stored(savedWithKey("sk-old-123456"));
        AiConfig incoming = new AiConfig();
        incoming.setApiKey("sk-new-987654");

        properties.save(incoming);

        assertEquals("sk-new-987654", incoming.getApiKey());
    }

    @Test
    void 保存的内容确实写进了配置表() {
        stored(new AiConfig());
        AiConfig incoming = new AiConfig();
        incoming.setEnabled(true);
        incoming.setBaseUrl("https://relay.example.com/v1");
        incoming.setApiKey("sk-relay-123456");
        incoming.setModel("gpt-4o-mini");
        incoming.setPersona("市场营销专业，做过两场线下活动");
        incoming.setTemperature(0.7);

        properties.save(incoming);

        ArgumentCaptor<AiConfig> captor = ArgumentCaptor.forClass(AiConfig.class);
        verify(configService).setJson(eq("ai"), captor.capture());
        AiConfig written = captor.getValue();
        assertTrue(written.isEnabled());
        assertEquals("https://relay.example.com/v1", written.getBaseUrl());
        assertEquals("sk-relay-123456", written.getApiKey());
        assertEquals("gpt-4o-mini", written.getModel());
        assertEquals("市场营销专业，做过两场线下活动", written.getPersona());
        assertEquals(0.7, written.getTemperature(), 1e-9);
    }

    @Test
    void 传入null时什么都不做() {
        properties.save(null);
        verify(configService, org.mockito.Mockito.never()).setJson(anyString(), any());
    }

    private static AiConfig savedWithKey(String key) {
        AiConfig existing = new AiConfig();
        existing.setApiKey(key);
        return existing;
    }

    // ------------------------------------------------------------------
    // 打码
    // ------------------------------------------------------------------

    @Test
    void 打码保留头尾又不会泄露全文() {
        String masked = AiProperties.maskApiKey("sk-test0011middlesecretmiddle0099zz");
        assertTrue(masked.startsWith("sk-test"), masked);
        assertTrue(masked.endsWith("99zz"), masked);
        assertTrue(masked.contains("…"), masked);
        assertFalse(masked.contains("middlesecretmiddle"), masked);
    }

    @Test
    void 空值和过短的值不给线索() {
        assertEquals("", AiProperties.maskApiKey(null));
        assertEquals("", AiProperties.maskApiKey("   "));
        assertEquals("****", AiProperties.maskApiKey("sk-1234"));
        assertEquals("****", AiProperties.maskApiKey("12345678"));
    }

    @Test
    void 打码会忽略首尾空白() {
        assertEquals("****", AiProperties.maskApiKey("  sk-1  "));
        assertTrue(AiProperties.maskApiKey("  sk-abcdef1234567890  ").startsWith("sk-abcd"));
    }
}
