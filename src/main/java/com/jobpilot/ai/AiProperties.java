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

    /** 用平台自备的中转，客户不用填接口 */
    public static final String MODE_PLATFORM = "platform";

    /** 用客户自己填的接口 */
    public static final String MODE_CUSTOM = "custom";

    private final ConfigService configService;

    public AiProperties(ConfigService configService) {
        this.configService = configService;
    }

    public AiConfig get() {
        return normalizeMode(configService.getJson(CONFIG_KEY, AiConfig.class, new AiConfig()));
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
        normalizeMode(incoming);
        configService.setJson(CONFIG_KEY, incoming);
        log.info("AI 配置已保存：enabled={} mode={} baseUrl={} model={} persona={}字",
                incoming.isEnabled(), incoming.getMode(), incoming.getBaseUrl(), incoming.getModel(),
                incoming.getPersona() == null ? 0 : incoming.getPersona().length());
    }

    /**
     * 把 mode 归一成 platform/custom。
     *
     * <p>只处理 null——{@link AiConfig#getMode()} 默认 null，表示这份配置是加
     * mode 字段之前存的。这种情况按"有没有填过自己的 key"决定：填过的老用户
     * 留在 custom（升级不该悄悄把人家的接口换成平台的），没填过的落到 platform。
     *
     * <p>只改内存不写库：读的时候归一就够了，下次保存自然带上正确值。
     */
    static AiConfig normalizeMode(AiConfig cfg) {
        if (cfg == null) {
            return null;
        }
        if (cfg.getMode() == null) {
            cfg.setMode(cfg.getApiKey() != null && !cfg.getApiKey().isBlank()
                    ? MODE_CUSTOM : MODE_PLATFORM);
        }
        return cfg;
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
