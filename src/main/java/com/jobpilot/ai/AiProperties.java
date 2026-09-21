package com.jobpilot.ai;

import com.jobpilot.system.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 配置的存取。config_key = ai，与四个平台的配置同表但互不影响。
 *
 * <p>apiKey 的写入规则（{@link #save}）：
 * <ul>
 *   <li>传 null / 不传这个字段 → 保持库里原来的 key</li>
 *   <li>传空串 → 清除 key</li>
 *   <li>传其他值 → 换成新值</li>
 * </ul>
 * 这样管理页可以把输入框留空表示"我没动 key"，不用先把明文拉回来再原样发回去。
 */
@Slf4j
@Service
public class AiProperties {

    public static final String CONFIG_KEY = "ai";

    private final ConfigService configService;

    public AiProperties(ConfigService configService) {
        this.configService = configService;
    }

    public AiConfig get() {
        return configService.getJson(CONFIG_KEY, AiConfig.class, new AiConfig());
    }

    public void save(AiConfig incoming) {
        AiConfig current = get();
        if (incoming == null) {
            return;
        }
        // null = 调用方没提这个字段，保留原值；空串是用户显式清除，放行
        if (incoming.getApiKey() == null) {
            incoming.setApiKey(current.getApiKey());
        }
        configService.setJson(CONFIG_KEY, incoming);
        log.info("AI 配置已保存：enabled={} baseUrl={} model={} persona={}字",
                incoming.isEnabled(), incoming.getBaseUrl(), incoming.getModel(),
                incoming.getPersona() == null ? 0 : incoming.getPersona().length());
    }

    /** 打码：只留头部和尾各几位，够用户认出是哪个 key，又不至于整个泄露 */
    public static String maskApiKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String k = key.trim();
        if (k.length() <= 8) {
            return "****";
        }
        return k.substring(0, Math.min(7, k.length() - 4)) + "…" + k.substring(k.length() - 4);
    }
}
