package com.jobpilot.ai;

import com.jobpilot.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 话术配置 API。
 *
 * <p>配置读写不受卡密门禁限制（门禁只拦以 /start 结尾的投递入口）：卡密失效时
 * 用户也要能进页面把 AI 配好，否则恢复了卡密还是发不出话术。
 *
 * <p>apiKey 的处理是本控制器唯一需要小心的地方：
 * <ul>
 *   <li>GET 只回打码值和"有没有配"，不回明文——管理页在局域网内也能打开</li>
 *   <li>PUT 时 apiKey 传 null/不传 = 不动原来的，空串 = 清除，其他 = 替换</li>
 *   <li>测试连接可以带临时覆盖值，用户改完地址想先试再存</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
public class AiController {

    /** 测试连接时的提示词：短、固定，方便判断"到底通不通" */
    private static final String TEST_SYSTEM = "你是连通性测试助手。";
    private static final String TEST_USER = "请用一句中文回答：你能正常工作吗？";

    private final AiProperties properties;
    private final AiService aiService;

    public AiController(AiProperties properties, AiService aiService) {
        this.properties = properties;
        this.aiService = aiService;
    }

    /** 配置（apiKey 打码） */
    @GetMapping("/config")
    public ApiResponse<AiConfigView> config() {
        AiConfig c = properties.get();
        return ApiResponse.ok(new AiConfigView(
                c.isEnabled(),
                c.getBaseUrl(),
                AiProperties.maskApiKey(c.getApiKey()),
                !isBlank(c.getApiKey()),
                c.getModel(),
                c.getPersona(),
                c.getTemperature()));
    }

    /**
     * 保存配置。语义和四个平台的 /api/{platform}/config 一致：<b>请求体就是完整配置</b>，
     * 没带的字段按默认值覆盖（所以管理页必须整表单一起提交，别只提交改动的字段）。
     *
     * <p>唯一的例外是 apiKey：传 null/不传 = 保留原值，空串 = 清除，其他 = 替换。
     * 因为管理页出于安全从不回显明文 key，输入框留空时不能区分"没改"和"清空"，
     * 所以由这个规则来兜。
     */
    @PutMapping("/config")
    public ApiResponse<AiConfigView> updateConfig(@RequestBody AiConfig incoming) {
        properties.save(incoming);
        return config();
    }

    /**
     * 测试连接：真发一次请求。请求体三个字段都可选，缺省的用已保存配置里的值，
     * 这样用户改完地址/Key 可以先测再存。
     */
    @PostMapping("/test")
    public ApiResponse<Map<String, Object>> test(@RequestBody(required = false) AiTestRequest body) {
        AiConfig saved = properties.get();
        String baseUrl = firstNonBlank(body != null ? body.baseUrl() : null, saved.getBaseUrl());
        String apiKey = firstNonBlank(body != null ? body.apiKey() : null, saved.getApiKey());
        String model = firstNonBlank(body != null ? body.model() : null, saved.getModel());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("baseUrl", AiService.normalizeBaseUrl(baseUrl));
        data.put("model", model);
        AiService.AiResult result = aiService.chat(baseUrl, apiKey, model,
                TEST_SYSTEM, TEST_USER, 0.3);
        data.put("ok", result.isOk());
        if (result.isOk()) {
            data.put("reply", AiService.cleanGreeting(result.text()));
        } else {
            data.put("error", result.error());
        }
        return ApiResponse.ok(data);
    }

    /** 拉取模型列表：中转站后台常见的"获取模型"按钮 */
    @GetMapping("/models")
    public ApiResponse<Map<String, Object>> models(@RequestParam(required = false) String baseUrl,
                                                   @RequestParam(required = false) String apiKey) {
        AiConfig saved = properties.get();
        String url = firstNonBlank(baseUrl, saved.getBaseUrl());
        String key = firstNonBlank(apiKey, saved.getApiKey());
        AiService.ModelResult result = aiService.models(url, key);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", result.isOk());
        data.put("baseUrl", AiService.normalizeBaseUrl(url));
        if (result.isOk()) {
            data.put("models", result.models());
        } else {
            data.put("error", result.error());
        }
        return ApiResponse.ok(data);
    }

    /** 给前端的视图：key 只给打码值 */
    public record AiConfigView(boolean enabled, String baseUrl, String apiKeyMasked,
                               boolean apiKeySet, String model, String persona, double temperature) {
    }

    public record AiTestRequest(String baseUrl, String apiKey, String model) {
    }

    private static String firstNonBlank(String a, String b) {
        return !isBlank(a) ? a.trim() : (b == null ? "" : b);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
