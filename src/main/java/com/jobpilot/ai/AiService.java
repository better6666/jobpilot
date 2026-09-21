package com.jobpilot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容接口客户端。官方（api.openai.com / api.deepseek.com）和第三方
 * 中转站走的都是同一套 {@code /chat/completions} 与 {@code /models}，所以一个
 * 客户端就够，差别只在地址和模型名。
 *
 * <p>为什么用 JDK 自带的 {@link java.net.http.HttpClient} 而不是 Spring 的
 * RestClient：这里只需要两个 POST/GET，用 JDK 的可以完全掌控超时和重试，
 * 也不用给打包版多背一个 HTTP 客户端的运行时。
 *
 * <p>三条硬规则：
 * <ul>
 *   <li><b>apiKey 永不进日志</b>——报错只带状态码和接口返回的 message，
 *       不带 Authorization 头</li>
 *   <li><b>4xx 不重试</b>（429 除外）：key 错、模型名错重试一次还是错，
 *       白等时间还拖慢投递节奏</li>
 *   <li><b>不传 max_tokens</b>：新模型（o1/gpt-5 一系）只认
 *       {@code max_completion_tokens}，传错参数直接 400。长度靠
 *       {@link #cleanGreeting} 截断</li>
 * </ul>
 */
@Slf4j
@Component
public class AiService {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /** 生成一句招呼语的读取超时。真机见过中转站 40 秒才返回，再长就放弃 */
    private static final int READ_TIMEOUT_MS = 45_000;
    private static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_MS = {600, 1800};
    /** 打招呼语长度上限：超了截到最后一个标点，避免半句话发出去 */
    static final int GREETING_MAX_LEN = 120;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AiService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
    }

    /** 一次调用的结果：成功带文本，失败带人能看懂的原因（不含 key） */
    public record AiResult(String text, String error) {
        public static AiResult ok(String text) {
            return new AiResult(text, null);
        }

        public static AiResult fail(String error) {
            return new AiResult(null, error);
        }

        public boolean isOk() {
            return error == null && text != null && !text.isBlank();
        }
    }

    /** 拉模型列表的结果 */
    public record ModelResult(List<String> models, String error) {
        public static ModelResult ok(List<String> models) {
            return new ModelResult(models, null);
        }

        public static ModelResult fail(String error) {
            return new ModelResult(List.of(), error);
        }

        public boolean isOk() {
            return error == null && !models.isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // 地址归一
    // ------------------------------------------------------------------

    /**
     * 把用户填的接口地址归一成 {@code https://host[/v1]} 形状。
     *
     * <p>要容忍四种常见填法（都是中转站文档里见过的）：
     * <pre>
     *   https://api.openai.com            → https://api.openai.com/v1
     *   https://api.openai.com/v1/        → https://api.openai.com/v1
     *   https://relay.example.com/v1      → 原样
     *   https://relay.example.com/openai  → 原样（中转站自定义前缀）
     *   https://x.com/v1/chat/completions → https://x.com/v1（粘了完整端点）
     * </pre>
     * 判断"有没有路径"用正则而不是 {@code URI.getPath()}：地址不合法时
     * {@code getPath()} 会抛异常，而这里要的是能返回一句错误提示。
     */
    static String normalizeBaseUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return "";
        }
        String lower = s.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "";
        }
        // 协议头统一小写：用户可能粘 "HTTPS://..." 进来，URI 认但不好看
        int schemeEnd = s.indexOf("://");
        s = s.substring(0, schemeEnd).toLowerCase(java.util.Locale.ROOT) + s.substring(schemeEnd);
        // 粘了整个端点进来：砍掉尾部的 /chat/completions
        if (lower.endsWith("/chat/completions")) {
            s = s.substring(0, s.length() - "/chat/completions".length());
            while (s.endsWith("/")) {
                s = s.substring(0, s.length() - 1);
            }
        }
        // 只有 scheme + host（没有路径）时补 /v1：官方和绝大多数中转站都是这个形状
        if (!s.matches("(?i)^https?://[^/]+/.*")) {
            s = s + "/v1";
        }
        return s;
    }

    // ------------------------------------------------------------------
    // 调用
    // ------------------------------------------------------------------

    /**
     * 对话补全。任何失败都返回 {@link AiResult#fail(String)}，不抛异常——
     * 投递循环里话术生成失败要能退回固定话术，不能让整个跑批挂掉。
     */
    public AiResult chat(String baseUrl, String apiKey, String model,
                         String systemPrompt, String userPrompt, double temperature) {
        String base = normalizeBaseUrl(baseUrl);
        if (base.isEmpty()) {
            return AiResult.fail("接口地址没配或不是 http(s) 地址");
        }
        if (isBlank(apiKey)) {
            return AiResult.fail("API Key 没配");
        }
        if (isBlank(model)) {
            return AiResult.fail("模型名没配");
        }

        String body;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("stream", false);
            payload.put("temperature", temperature);
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt));
            messages.add(Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt));
            payload.put("messages", messages);
            body = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return AiResult.fail("请求体构造失败: " + e.getMessage());
        }

        String lastError = "未知错误";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpRequest request;
            try {
                request = HttpRequest.newBuilder()
                        .uri(URI.create(base + "/chat/completions"))
                        .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
            } catch (IllegalArgumentException e) {
                // 地址里带空格/中文之类，URI.create 直接拒
                return AiResult.fail("接口地址不合法: " + e.getMessage());
            }
            try {
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                if (code >= 200 && code < 300) {
                    String content = extractContent(response.body());
                    if (content == null || content.isBlank()) {
                        return AiResult.fail("接口返回 200 但没有内容（choices 为空）");
                    }
                    return AiResult.ok(content);
                }
                lastError = describeHttpError(code, response.body());
                // 4xx（429 除外）是确定性问题，重试没意义
                if (code >= 400 && code < 500 && code != 429) {
                    return AiResult.fail(lastError);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AiResult.fail("调用被中断");
            } catch (Exception e) {
                lastError = "连接失败或超时: " + e.getClass().getSimpleName();
            }
            if (attempt < MAX_ATTEMPTS) {
                sleep(BACKOFF_MS[Math.min(attempt - 1, BACKOFF_MS.length - 1)]);
            }
        }
        return AiResult.fail(lastError);
    }

    /**
     * 走平台中转：把提示词发给卡密服务端，由服务端拿自己存的 key 去调中转。
     *
     * <p>客户端全程拿不到那把 key——买卡的人从安装目录里翻不出来，也就没法
     * 把平台的额度搬去自用。这是"平台提供话术"和"把 key 发给客户端自己调"
     * 的本质区别，后者等于把 key 白送。
     *
     * <p>失败语义和 {@link #chat} 一致：返回 {@link AiResult#fail} 不抛异常，
     * 4xx 不重试（429 除外），调用方退回固定话术。
     */
    public AiResult chatPlatform(String apiBase, String token, String deviceId,
                                 String systemPrompt, String userPrompt, double temperature) {
        String base = apiBase == null ? "" : apiBase.trim().replaceAll("/+$", "");
        if (base.isEmpty()) {
            return AiResult.fail("卡密服务端地址没配");
        }
        if (isBlank(token) || isBlank(deviceId)) {
            return AiResult.fail("卡密未激活，用不了平台话术");
        }

        String body;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("token", token);
            payload.put("device_id", deviceId);
            payload.put("system", systemPrompt == null ? "" : systemPrompt);
            payload.put("user", userPrompt == null ? "" : userPrompt);
            payload.put("temperature", temperature);
            body = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return AiResult.fail("请求体构造失败: " + e.getMessage());
        }

        String lastError = "未知错误";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpRequest request;
            try {
                request = HttpRequest.newBuilder()
                        .uri(URI.create(base + "/api/ai/chat"))
                        .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
            } catch (IllegalArgumentException e) {
                return AiResult.fail("卡密服务端地址不合法: " + e.getMessage());
            }
            try {
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                if (code >= 200 && code < 300) {
                    JsonNode root;
                    try {
                        root = objectMapper.readTree(response.body());
                    } catch (Exception e) {
                        return AiResult.fail("平台响应不是合法 JSON");
                    }
                    // 服务端可能 200 但业务失败（平台没配中转、卡密过期），
                    // 这时候 success=false 且带 message，要透传不能当成空内容
                    if (!root.path("success").asBoolean(false)) {
                        String detail = errorMessageFrom(root);
                        return AiResult.fail(detail.isBlank() ? "平台返回异常" : detail);
                    }
                    String text = root.path("data").path("text").asText(null);
                    if (text == null || text.isBlank()) {
                        return AiResult.fail("平台返回 200 但没有话术内容");
                    }
                    return AiResult.ok(text);
                }
                lastError = describePlatformError(code, response.body());
                if (code >= 400 && code < 500 && code != 429) {
                    return AiResult.fail(lastError);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AiResult.fail("调用被中断");
            } catch (Exception e) {
                lastError = "连接卡密服务端失败或超时: " + e.getClass().getSimpleName();
            }
            if (attempt < MAX_ATTEMPTS) {
                sleep(BACKOFF_MS[Math.min(attempt - 1, BACKOFF_MS.length - 1)]);
            }
        }
        return AiResult.fail(lastError);
    }

    /**
     * 平台代理的错误翻成人话。服务端给的 message 已经是给人看的，优先带上；
     * 状态码只用于在服务端没说话时补一句原因。
     */
    private String describePlatformError(int code, String body) {
        String detail = errorMessageFrom(body);
        String reason = switch (code) {
            case 401 -> "卡密激活信息失效，请重新激活";
            case 403 -> "卡密不可用（已到期或次数用完）";
            case 404 -> "卡密服务端没有这个接口（服务端版本可能太旧）";
            case 429 -> "请求过于频繁，稍后再试";
            case 503 -> "平台没配 AI 中转";
            default -> code >= 500 ? "平台服务异常" : "平台返回 " + code;
        };
        return detail.isBlank() ? reason : reason + "：" + detail;
    }

    /** 拉取模型列表（管理页"拉取模型"按钮） */
    public ModelResult models(String baseUrl, String apiKey) {
        String base = normalizeBaseUrl(baseUrl);
        if (base.isEmpty()) {
            return ModelResult.fail("接口地址没配或不是 http(s) 地址");
        }
        if (isBlank(apiKey)) {
            return ModelResult.fail("API Key 没配");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/models"))
                    .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code < 200 || code >= 300) {
                return ModelResult.fail(describeHttpError(code, response.body()));
            }
            List<String> ids = new ArrayList<>();
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode arr = root.path("data");
            if (!arr.isArray()) {
                arr = root.isArray() ? root : null;
            }
            if (arr != null) {
                for (JsonNode node : arr) {
                    String id = node.path("id").asText("");
                    if (!id.isBlank()) {
                        ids.add(id);
                    }
                }
            }
            if (ids.isEmpty()) {
                return ModelResult.fail("接口没返回任何模型（/models 为空）");
            }
            ids.sort(String::compareTo);
            return ModelResult.ok(ids);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ModelResult.fail("调用被中断");
        } catch (Exception e) {
            return ModelResult.fail("连接失败或超时: " + e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // 响应解析与清洗
    // ------------------------------------------------------------------

    /** 从 chat/completions 响应里取 choices[0].message.content */
    private String extractContent(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return null;
            }
            return choices.get(0).path("message").path("content").asText(null);
        } catch (Exception e) {
            log.debug("解析接口响应失败: {}", e.getMessage());
            return null;
        }
    }

    /** 把 HTTP 错误翻成人话。只取接口返回的 message，不带请求头（那里有 key） */
    private String describeHttpError(int code, String body) {
        String detail = errorMessageFrom(body);
        String reason = switch (code) {
            case 401, 403 -> "接口拒绝了（key 无效或没有该模型权限）";
            case 404 -> "接口地址或模型不存在";
            case 429 -> "接口限流";
            default -> code >= 500 ? "接口服务端错误" : "接口返回 " + code;
        };
        return detail.isBlank() ? reason : reason + "：" + detail;
    }

    /**
     * 从错误响应里取 message。取不到就退回响应前 200 字——网关的 HTML 错误页
     * 也能看出个大概。只读 body，绝不碰请求头（那里有 key）。
     */
    private String errorMessageFrom(String body) {
        try {
            if (body == null || body.isBlank()) {
                return "";
            }
            String msg = errorMessageFrom(objectMapper.readTree(body));
            return msg.isBlank() ? (body.length() > 200 ? body.substring(0, 200) : body) : msg;
        } catch (Exception ignore) {
            // 响应不是 JSON（网关的 HTML 错误页），用状态码就够了
            return "";
        }
    }

    /** 从已解析的 JSON 里取 message；没有就空串 */
    private String errorMessageFrom(JsonNode root) {
        JsonNode msg = root.path("error").path("message");
        if (msg.isTextual()) {
            return msg.asText();
        }
        if (root.path("message").isTextual()) {
            return root.path("message").asText();
        }
        return "";
    }

    /**
     * 清洗模型输出：围栏、引号、"话术："这类前缀、多余空白都去掉，
     * 超长截到最后一个标点。模型偶尔会把解释和话术一起输出，只取第一段。
     *
     * <p>清洗要跑两遍：模型很爱把话术包在代码围栏里再补一段解释，这时候
     * 围栏的结尾不在整串末尾，第一遍剥不掉，得等截完第一段之后再剥一次。
     */
    static String cleanGreeting(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        s = stripDecorations(s);
        // 多段输出只留第一段（第二段往往是解释）
        int cut = s.length();
        for (String sep : new String[]{"\n\n", "\r\n\r\n"}) {
            int idx = s.indexOf(sep);
            if (idx >= 0 && idx < cut) {
                cut = idx;
            }
        }
        s = stripDecorations(s.substring(0, cut));
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() > GREETING_MAX_LEN) {
            s = s.substring(0, GREETING_MAX_LEN);
            int last = Math.max(s.lastIndexOf('。'), Math.max(s.lastIndexOf('！'), s.lastIndexOf('？')));
            int comma = Math.max(s.lastIndexOf('，'), Math.max(s.lastIndexOf('；'), s.lastIndexOf('!')));
            int boundary = Math.max(last, comma);
            if (boundary > GREETING_MAX_LEN / 2) {
                s = s.substring(0, boundary + 1);
            }
            s = s.trim();
        }
        return s.isEmpty() ? null : s;
    }

    /** 去掉围栏、包裹的引号和"话术："这类自带标签 */
    private static String stripDecorations(String s) {
        s = s.replaceAll("(?s)^\\s*```[a-zA-Z]*\\s*", "").replaceAll("(?s)\\s*```\\s*$", "");
        s = s.replaceAll("^[\"“”'‘’]+|[\"“”'‘’]+$", "");
        s = s.replaceFirst("^\\s*(话术|打招呼语|招呼语|回复|输出)\\s*[:：]\\s*", "");
        return s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
