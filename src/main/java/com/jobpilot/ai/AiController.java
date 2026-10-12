package com.jobpilot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.common.ApiResponse;
import com.jobpilot.license.LicenseClient;
import com.jobpilot.license.LicenseProperties;
import com.jobpilot.license.LicenseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
            // 客户在平台上选了模型就用他选的，没选过（空）就是平台后台那个默认值
            String platformModel = firstNonBlank(saved.getModel(), platformInfo().model());
            data.put("model", platformModel);
            result = aiService.chatPlatform(licenseProperties.getApiBase(),
                    credentials.get("token"), credentials.get("device_id"),
                    TEST_SYSTEM, TEST_USER, 0.3, saved.getModel());
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
     * <p>自有接口模式下直接拿客户填的地址和 key 去问中转；平台模式下客户端
     * 一个字符的接口信息都没有，改走卡密服务端的 {@code /api/ai/models}——
     * 由服务端用它自己那把中转 key 去拉，回来的只有模型名和分组名。
     */
    @GetMapping("/models")
    public ApiResponse<Map<String, Object>> models(@RequestParam(required = false) String baseUrl,
                                                   @RequestParam(required = false) String apiKey) {
        AiConfig saved = properties.get();
        boolean platform = !AiProperties.MODE_CUSTOM.equals(saved.getMode());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", platform ? AiProperties.MODE_PLATFORM : AiProperties.MODE_CUSTOM);

        if (platform) {
            return ApiResponse.ok(platformModels(data));
        }

        String url = firstNonBlank(baseUrl, saved.getBaseUrl());
        String key = firstNonBlank(apiKey, saved.getApiKey());
        AiService.ModelResult result = aiService.models(url, key);
        data.put("ok", result.isOk());
        data.put("baseUrl", AiService.normalizeBaseUrl(url));
        if (result.isOk()) {
            data.put("models", result.models());
            // 站点不暴露分组接口时这两个字段干脆不给，桌面端就按老样子只显示模型名
            if (!result.details().isEmpty()) {
                data.put("modelGroups", result.details());
                data.put("availableCount", (int) result.details().values().stream()
                        .filter(AiService.ModelInfo::available).count());
            }
        } else {
            data.put("error", result.error());
        }
        return ApiResponse.ok(data);
    }

    /**
     * 平台模式下的拉取：让卡密服务端用它自己那把中转 key 去问，客户端只接模型名。
     *
     * <p>服务端那台中转的地址和 key 一个字都不往客户端传——和 {@code /api/ai/chat}
     * 同一个道理，翻安装目录的人拿不到平台的额度。
     */
    private Map<String, Object> platformModels(Map<String, Object> data) {
        Map<String, String> credentials = licenseService.proxyCredentials();
        if (credentials == null) {
            data.put("ok", false);
            data.put("error", "卡密未激活，先激活才能拉平台的模型列表");
            return data;
        }
        JsonNode node;
        try {
            node = licenseClient.post(licenseProperties.getApiBase(), "/api/ai/models",
                    Map.of("token", credentials.get("token"),
                            "device_id", credentials.get("device_id")));
        } catch (LicenseClient.LicenseServerException e) {
            // 服务端的话已经是给人看的（平台没配中转 / 中转挂了 / 拉得太频繁），原样带上
            data.put("ok", false);
            data.put("error", e.getMessage());
            return data;
        }
        if (node == null) {
            data.put("ok", false);
            data.put("error", "连不上卡密服务端，暂时拉不了平台的模型列表");
            return data;
        }
        JsonNode payload = node.path("data");
        List<String> names = new ArrayList<>();
        payload.path("models").forEach(item -> names.add(item.asText("")));
        if (names.isEmpty()) {
            data.put("ok", false);
            data.put("error", "平台的中转没有返回模型");
            return data;
        }
        data.put("ok", true);
        data.put("models", names);
        data.put("platformModel", payload.path("model").asText(""));
        JsonNode groups = payload.path("model_groups");
        // 键名换成客户端认的样子，两种模式下 NativeMainWindow 走同一段渲染
        if (groups.isObject() && groups.size() > 0) {
            data.put("modelGroups", groups);
            data.put("availableCount", payload.path("available_count").asInt(names.size()));
        }
        return data;
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
