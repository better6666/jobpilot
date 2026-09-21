package com.jobpilot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.common.ApiResponse;
import com.jobpilot.license.LicenseClient;
import com.jobpilot.license.LicenseProperties;
import com.jobpilot.license.LicenseService;
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
 *
 * <p>平台模式（{@link AiProperties#MODE_PLATFORM}）下客户端不存任何接口信息，
 * 中转的地址和 key 都在卡密服务端。所以页面上的"接口地址/Key/模型"三个字段
 * 整个藏起来，只留人设和温度——客户要填的只有"我是谁"。
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
    private final LicenseService licenseService;
    private final LicenseProperties licenseProperties;
    private final LicenseClient licenseClient;

    public AiController(AiProperties properties, AiService aiService,
                        LicenseService licenseService, LicenseProperties licenseProperties,
                        LicenseClient licenseClient) {
        this.properties = properties;
        this.aiService = aiService;
        this.licenseService = licenseService;
        this.licenseProperties = licenseProperties;
        this.licenseClient = licenseClient;
    }

    /** 配置（apiKey 打码）+ 平台中转的可用状态 */
    @GetMapping("/config")
    public ApiResponse<AiConfigView> config() {
        AiConfig c = properties.get();
        PlatformInfo platform = platformInfo();
        return ApiResponse.ok(new AiConfigView(
                c.isEnabled(),
                c.getMode(),
                c.getBaseUrl(),
                AiProperties.maskApiKey(c.getApiKey()),
                !isBlank(c.getApiKey()),
                c.getModel(),
                c.getPersona(),
                c.getTemperature(),
                platform.available(),
                platform.model(),
                platform.message()));
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
     * 测试连接：真发一次请求。请求体四个字段都可选，缺省的用已保存配置里的值，
     * 这样用户改完地址/Key 可以先测再存。
     *
     * <p>平台模式下不读本地任何接口配置，直接走服务端代理——测的就是客户
     * 真正会走的那条路。
     */
    @PostMapping("/test")
    public ApiResponse<Map<String, Object>> test(@RequestBody(required = false) AiTestRequest body) {
        AiConfig saved = properties.get();
        String mode = firstNonBlank(body != null ? body.mode() : null, saved.getMode());
        boolean platform = !AiProperties.MODE_CUSTOM.equals(mode);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", platform ? AiProperties.MODE_PLATFORM : AiProperties.MODE_CUSTOM);

        AiService.AiResult result;
        if (platform) {
            Map<String, String> credentials = licenseService.proxyCredentials();
            if (credentials == null) {
                data.put("ok", false);
                data.put("error", "卡密未激活，先激活才能用平台话术");
                return ApiResponse.ok(data);
            }
            data.put("model", platformInfo().model());
            result = aiService.chatPlatform(licenseProperties.getApiBase(),
                    credentials.get("token"), credentials.get("device_id"),
                    TEST_SYSTEM, TEST_USER, 0.3);
        } else {
            String baseUrl = firstNonBlank(body != null ? body.baseUrl() : null, saved.getBaseUrl());
            String apiKey = firstNonBlank(body != null ? body.apiKey() : null, saved.getApiKey());
            String model = firstNonBlank(body != null ? body.model() : null, saved.getModel());
            data.put("baseUrl", AiService.normalizeBaseUrl(baseUrl));
            data.put("model", model);
            result = aiService.chat(baseUrl, apiKey, model, TEST_SYSTEM, TEST_USER, 0.3);
        }

        data.put("ok", result.isOk());
        if (result.isOk()) {
            data.put("reply", AiService.cleanGreeting(result.text()));
        } else {
            data.put("error", result.error());
        }
        return ApiResponse.ok(data);
    }

    /**
     * 拉取模型列表：中转站后台常见的"获取模型"按钮。
     *
     * <p>平台模式下模型由平台定，客户端没有 /models 可拉，直接说明白，
     * 别让用户对着一个没反应的按钮发呆。
     */
    @GetMapping("/models")
    public ApiResponse<Map<String, Object>> models(@RequestParam(required = false) String baseUrl,
                                                   @RequestParam(required = false) String apiKey) {
        AiConfig saved = properties.get();
        boolean platform = !AiProperties.MODE_CUSTOM.equals(saved.getMode());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", platform ? AiProperties.MODE_PLATFORM : AiProperties.MODE_CUSTOM);

        if (platform) {
            data.put("ok", false);
            data.put("error", "模型由平台统一指定，不能自己拉取");
            return ApiResponse.ok(data);
        }

        String url = firstNonBlank(baseUrl, saved.getBaseUrl());
        String key = firstNonBlank(apiKey, saved.getApiKey());
        AiService.ModelResult result = aiService.models(url, key);
        data.put("ok", result.isOk());
        data.put("baseUrl", AiService.normalizeBaseUrl(url));
        if (result.isOk()) {
            data.put("models", result.models());
        } else {
            data.put("error", result.error());
        }
        return ApiResponse.ok(data);
    }

    /**
     * 问卡密服务端：平台中转配了没、用的什么模型。
     *
     * <p>拿不到就当没有（message 里说清原因），不让服务端的一次抖动把
     * 整个配置页拖成读取失败——客户还能改成"我自己的接口"。
     */
    private PlatformInfo platformInfo() {
        JsonNode node = licenseClient.get(licenseProperties.getApiBase(), "/api/ai/info");
        if (node == null) {
            return new PlatformInfo(false, null, "连不上卡密服务端，平台话术暂时不可用");
        }
        JsonNode data = node.path("data");
        boolean configured = data.path("configured").asBoolean(false);
        String model = data.path("model").isTextual() ? data.path("model").asText(null) : null;
        if (!configured) {
            return new PlatformInfo(false, null, "平台没开 AI 中转，可改用「我自己的接口」");
        }
        return new PlatformInfo(true, model, null);
    }

    /** 给前端的视图：key 只给打码值 */
    public record AiConfigView(boolean enabled, String mode, String baseUrl, String apiKeyMasked,
                               boolean apiKeySet, String model, String persona, double temperature,
                               boolean platformAvailable, String platformModel, String platformMessage) {
    }

    public record AiTestRequest(String mode, String baseUrl, String apiKey, String model) {
    }

    /** 平台中转的可用状态：available=平台配没配，model=平台用什么模型 */
    private record PlatformInfo(boolean available, String model, String message) {
    }

    private static String firstNonBlank(String a, String b) {
        return !isBlank(a) ? a.trim() : (b == null ? "" : b);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
