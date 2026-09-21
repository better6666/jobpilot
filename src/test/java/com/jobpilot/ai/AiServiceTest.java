package com.jobpilot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI 客户端的真实 HTTP 行为。
 *
 * <p>本地起一个假 OpenAI 兼容服务，验的是真发出去的请求：地址怎么拼、
 * Authorization 怎么带、哪些状态码该重试、响应怎么解析。这些用 mock 的
 * HttpClient 一个都验不到，而它们恰恰是"接官方还是接中转站"的分水岭。
 */
class AiServiceTest {

    private static final String CHAT_PATH = "/v1/chat/completions";
    private static final String MODELS_PATH = "/v1/models";

    private HttpServer server;
    private AiService aiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 每个路径被请求了几次，用来验证重试次数 */
    private final Map<String, AtomicInteger> hits = new HashMap<>();
    /** 请求体，按到达顺序 */
    private final List<String> bodies = new ArrayList<>();
    /** Authorization 头，按到达顺序；缺失记空串 */
    private final List<String> authHeaders = new ArrayList<>();

    private record Reply(int status, String body) {
    }

    /**
     * 按 (path, body) 决定回什么；默认回一句正常的话术。
     *
     * /api/ 开头的是平台代理，回的是服务端那层壳（success + data.text）；
     * 其余是中转，回的是 OpenAI 原样（choices[0].message.content）。
     */
    private BiFunction<String, String, Reply> responder =
            (path, body) -> path.startsWith("/api/")
                    ? new Reply(200, "{\"success\":true,\"data\":{\"text\":\"您好\"}}")
                    : new Reply(200, "{\"choices\":[{\"message\":{\"content\":\"您好\"}}]}");

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        aiService = new AiService(objectMapper);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
        bodies.add(body);
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        authHeaders.add(auth == null ? "" : auth);
        Reply reply = responder.apply(path, body);
        byte[] out = reply.body() == null ? new byte[0] : reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    /** 假接口的地址：只有 scheme+host，正好走"补 /v1"那条路 */
    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private int hitsOf(String path) {
        AtomicInteger c = hits.get(path);
        return c == null ? 0 : c.get();
    }

    private JsonNode sentChatBody() throws Exception {
        assertEquals(1, bodies.size(), "应该只发过一个请求体");
        return objectMapper.readTree(bodies.get(0));
    }

    // ------------------------------------------------------------------
    // 地址归一
    // ------------------------------------------------------------------

    @Test
    void 官方地址补上v1() {
        assertEquals("https://api.openai.com/v1", AiService.normalizeBaseUrl("https://api.openai.com"));
    }

    @Test
    void 尾斜杠被去掉() {
        assertEquals("https://api.openai.com/v1", AiService.normalizeBaseUrl("https://api.openai.com/v1/"));
    }

    @Test
    void 中转站自定义前缀原样保留() {
        assertEquals("https://relay.example.com/openai", AiService.normalizeBaseUrl("https://relay.example.com/openai"));
    }

    @Test
    void 粘了整个端点只砍掉chatCompletions() {
        assertEquals("https://relay.example.com/v1",
                AiService.normalizeBaseUrl("https://relay.example.com/v1/chat/completions"));
    }

    @Test
    void 带路径的地址不会再补v1() {
        assertEquals("https://relay.example.com/api/v3", AiService.normalizeBaseUrl("https://relay.example.com/api/v3"));
    }

    @Test
    void 非http地址一律拒绝() {
        assertEquals("", AiService.normalizeBaseUrl("api.openai.com"));
        assertEquals("", AiService.normalizeBaseUrl("ftp://x.com/v1"));
        assertEquals("", AiService.normalizeBaseUrl("  "));
        assertEquals("", AiService.normalizeBaseUrl(null));
    }

    @Test
    void 大小写不敏感的协议头也认() {
        assertEquals("https://api.openai.com/v1", AiService.normalizeBaseUrl("HTTPS://api.openai.com"));
    }

    // ------------------------------------------------------------------
    // 真实请求
    // ------------------------------------------------------------------

    @Test
    void 请求打到v1下的chatCompletions并带上Bearer头() throws Exception {
        AiService.AiResult r = aiService.chat(baseUrl(), "sk-test-123456", "gpt-4o-mini",
                "你是助手", "写一句", 0.9);

        assertTrue(r.isOk());
        assertEquals("您好", r.text());
        assertEquals(1, hitsOf(CHAT_PATH));
        assertEquals("Bearer sk-test-123456", authHeaders.get(0));
    }

    @Test
    void 请求体不带maxTokens且关闭流式() throws Exception {
        aiService.chat(baseUrl(), "sk-test-123456", "gpt-4o-mini", "sys", "user", 0.7);

        JsonNode body = sentChatBody();
        assertEquals("gpt-4o-mini", body.path("model").asText());
        assertFalse(body.path("stream").asBoolean(true), "必须关流式，否则拿到的是 SSE 分片");
        assertEquals(0.7, body.path("temperature").asDouble(), 1e-9);
        // 新模型只认 max_completion_tokens，传 max_tokens 会直接 400，所以两个都不传
        assertTrue(body.path("max_tokens").isMissingNode(), "不该传 max_tokens");
        assertTrue(body.path("max_completion_tokens").isMissingNode(), "不该传 max_completion_tokens");
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertEquals("sys", body.path("messages").get(0).path("content").asText());
        assertEquals("user", body.path("messages").get(1).path("role").asText());
        assertEquals("user", body.path("messages").get(1).path("content").asText());
    }

    @Test
    void 地址带了完整端点也能打通() throws Exception {
        AiService.AiResult r = aiService.chat(baseUrl() + "/v1/chat/completions", "sk-abc", "m", "s", "u", 0.5);
        assertTrue(r.isOk());
        assertEquals(1, hitsOf(CHAT_PATH), "砍掉 /chat/completions 后不能再拼一遍");
    }

    @Test
    void key没配时直接失败不发请求() {
        AiService.AiResult r = aiService.chat(baseUrl(), "  ", "m", "s", "u", 0.5);
        assertFalse(r.isOk());
        assertEquals("API Key 没配", r.error());
        assertEquals(0, hitsOf(CHAT_PATH));
    }

    @Test
    void 模型名没配时直接失败不发请求() {
        AiService.AiResult r = aiService.chat(baseUrl(), "sk-x", "", "s", "u", 0.5);
        assertFalse(r.isOk());
        assertEquals("模型名没配", r.error());
        assertEquals(0, hitsOf(CHAT_PATH));
    }

    @Test
    void 地址不合法时给出可读原因() {
        AiService.AiResult r = aiService.chat("http://127.0.0.1:1 /v1", "sk-x", "m", "s", "u", 0.5);
        assertFalse(r.isOk());
        assertTrue(r.error().startsWith("接口地址不合法"), r.error());
    }

    @Test
    void 鉴权失败只发一次不重试() {
        responder = (path, body) -> new Reply(401,
                "{\"error\":{\"message\":\"Incorrect API key provided\"}}");

        AiService.AiResult r = aiService.chat(baseUrl(), "sk-wrong", "m", "s", "u", 0.5);

        assertFalse(r.isOk());
        assertEquals(1, hitsOf(CHAT_PATH), "401 是确定性问题，重试没意义");
        assertTrue(r.error().contains("接口拒绝了"), r.error());
        assertTrue(r.error().contains("Incorrect API key provided"), r.error());
        // 报错里不能带 key 本身
        assertFalse(r.error().contains("sk-wrong"), "错误信息泄露了 key");
    }

    @Test
    void 状态404翻成地址或模型不存在() {
        responder = (path, body) -> new Reply(404, "{\"error\":{\"message\":\"model not found\"}}");
        AiService.AiResult r = aiService.chat(baseUrl(), "sk-x", "no-such-model", "s", "u", 0.5);
        assertFalse(r.isOk());
        assertTrue(r.error().contains("接口地址或模型不存在"), r.error());
        assertEquals(1, hitsOf(CHAT_PATH));
    }

    @Test
    void 限流后会重试并最终成功() {
        AtomicInteger n = new AtomicInteger();
        responder = (path, body) -> n.incrementAndGet() == 1
                ? new Reply(429, "{\"error\":{\"message\":\"rate limited\"}}")
                : new Reply(200, "{\"choices\":[{\"message\":{\"content\":\"您好，第二次成功\"}}]}");

        AiService.AiResult r = aiService.chat(baseUrl(), "sk-x", "m", "s", "u", 0.5);

        assertTrue(r.isOk(), r.error());
        assertEquals("您好，第二次成功", r.text());
        assertEquals(2, hitsOf(CHAT_PATH));
    }

    @Test
    void 服务端错误重试三次后放弃() {
        responder = (path, body) -> new Reply(500, "<html>gateway error</html>");
        AiService.AiResult r = aiService.chat(baseUrl(), "sk-x", "m", "s", "u", 0.5);
        assertFalse(r.isOk());
        assertEquals(3, hitsOf(CHAT_PATH), "最多三次");
        assertTrue(r.error().startsWith("接口服务端错误"), r.error());
    }

    @Test
    void 返回200但内容为空算失败() {
        responder = (path, body) -> new Reply(200, "{\"choices\":[]}");
        AiService.AiResult r = aiService.chat(baseUrl(), "sk-x", "m", "s", "u", 0.5);
        assertFalse(r.isOk());
        assertTrue(r.error().contains("没有内容"), r.error());
    }

    @Test
    void 连接被拒时返回连接失败() {
        // 占着一个端口再关掉，拿到一个一定连不上的地址
        AiService.AiResult r = aiService.chat("http://127.0.0.1:1/v1", "sk-x", "m", "s", "u", 0.5);
        assertFalse(r.isOk());
        assertTrue(r.error().startsWith("连接失败或超时"), r.error());
    }

    // ------------------------------------------------------------------
    // 模型列表
    // ------------------------------------------------------------------

    @Test
    void 模型列表取data里的id并排序() {
        responder = (path, body) -> new Reply(200,
                "{\"data\":[{\"id\":\"gpt-4o-mini\"},{\"id\":\"deepseek-chat\"},{\"id\":\"gpt-4o-mini\"}]}");

        AiService.ModelResult r = aiService.models(baseUrl(), "sk-x");

        assertTrue(r.isOk());
        assertEquals(List.of("deepseek-chat", "gpt-4o-mini", "gpt-4o-mini"), r.models());
        assertEquals(1, hitsOf(MODELS_PATH));
        assertEquals("Bearer sk-x", authHeaders.get(0));
    }

    @Test
    void 裸数组形式的模型列表也认() {
        responder = (path, body) -> new Reply(200, "[{\"id\":\"b\"},{\"id\":\"a\"}]");
        AiService.ModelResult r = aiService.models(baseUrl(), "sk-x");
        assertTrue(r.isOk());
        assertEquals(List.of("a", "b"), r.models());
    }

    @Test
    void 模型列表为空算失败() {
        responder = (path, body) -> new Reply(200, "{\"data\":[]}");
        AiService.ModelResult r = aiService.models(baseUrl(), "sk-x");
        assertFalse(r.isOk());
        assertTrue(r.error().contains("没返回任何模型"), r.error());
    }

    @Test
    void 模型列表鉴权失败给出可读原因() {
        responder = (path, body) -> new Reply(403, "{\"error\":{\"message\":\"forbidden\"}}");
        AiService.ModelResult r = aiService.models(baseUrl(), "sk-x");
        assertFalse(r.isOk());
        assertTrue(r.error().contains("接口拒绝了"), r.error());
        assertFalse(r.error().contains("sk-x"));
    }

    // ------------------------------------------------------------------
    // 输出清洗
    // ------------------------------------------------------------------

    @Test
    void 去掉代码围栏() {
        assertEquals("您好，看到贵司在招陈列设计", AiService.cleanGreeting("```text\n您好，看到贵司在招陈列设计\n```"));
    }

    @Test
    void 围栏后面还跟着解释时围栏也要剥干净() {
        // 实测踩过：围栏不在整串末尾，第一遍剥不掉，留着会随第一段一起发出去
        String raw = "```text\n话术：您好，看到贵司在招陈列设计助理，我做过两季橱窗陈列，想聊聊这个岗位。\n```\n\n解释：这里结合了 JD。";
        String cleaned = AiService.cleanGreeting(raw);
        assertEquals("您好，看到贵司在招陈列设计助理，我做过两季橱窗陈列，想聊聊这个岗位。", cleaned);
    }

    @Test
    void 去掉包裹的引号() {
        assertEquals("您好啊", AiService.cleanGreeting("\"您好啊\""));
        assertEquals("您好啊", AiService.cleanGreeting("“您好啊”"));
    }

    @Test
    void 去掉自带的话术标签() {
        assertEquals("您好，想聊聊这个岗位", AiService.cleanGreeting("话术：您好，想聊聊这个岗位"));
        assertEquals("您好，想聊聊这个岗位", AiService.cleanGreeting("打招呼语: 您好，想聊聊这个岗位"));
    }

    @Test
    void 多段输出只留第一段() {
        assertEquals("您好，想聊聊陈列设计。", AiService.cleanGreeting("您好，想聊聊陈列设计。\n\n解释：这里结合了JD……"));
    }

    @Test
    void 多余空白压成一个空格() {
        assertEquals("您好 想聊聊", AiService.cleanGreeting("您好\n   想聊聊"));
    }

    @Test
    void 空输出返回null() {
        assertNull(AiService.cleanGreeting(null));
        assertNull(AiService.cleanGreeting("   "));
        assertNull(AiService.cleanGreeting("```\n\n```"));
    }

    @Test
    void 超长截到最后一个标点() {
        String raw = "一二三四五，六七八九十，".repeat(12) + "尾巴";
        assertTrue(raw.length() > AiService.GREETING_MAX_LEN);

        String cleaned = AiService.cleanGreeting(raw);

        assertTrue(cleaned.length() < raw.length());
        assertTrue(cleaned.length() <= AiService.GREETING_MAX_LEN);
        assertTrue(cleaned.endsWith("，"), cleaned);
        assertTrue(cleaned.length() > AiService.GREETING_MAX_LEN / 2, "截太狠不如不发");
    }

    @Test
    void 超长但没有标点时按长度硬截() {
        String raw = "您好".repeat(65); // 130 字，无标点
        String cleaned = AiService.cleanGreeting(raw);
        assertEquals(AiService.GREETING_MAX_LEN, cleaned.length());
    }

    // ------------------------------------------------------------------
    // 结果记录
    // ------------------------------------------------------------------

    @Test
    void 结果记录的好坏判定() {
        assertTrue(AiService.AiResult.ok("您好").isOk());
        assertFalse(AiService.AiResult.ok("  ").isOk());
        assertFalse(AiService.AiResult.ok(null).isOk());
        assertFalse(AiService.AiResult.fail("x").isOk());
        assertNull(AiService.AiResult.fail("x").text());
        assertTrue(AiService.ModelResult.ok(List.of("a")).isOk());
        assertFalse(AiService.ModelResult.ok(List.of()).isOk());
        assertFalse(AiService.ModelResult.fail("x").isOk());
        assertEquals(List.of(), AiService.ModelResult.fail("x").models());
    }

    // ------------------------------------------------------------------
    // 平台代理（token + device_id，key 留在服务端）
    // ------------------------------------------------------------------

    private static final String PLATFORM_PATH = "/api/ai/chat";

    @Test
    void 平台代理请求打在服务端的ai路径上并带上凭证() throws Exception {
        AiService.AiResult r = aiService.chatPlatform(baseUrl(), "tok-123", "dev-456",
                "系统提示", "用户提示", 0.7);

        assertTrue(r.isOk(), r.error());
        assertEquals(1, hitsOf(PLATFORM_PATH), "应该只打服务端，不打中转");
        assertEquals(0, hitsOf(CHAT_PATH), "平台模式绝不能直连中转——那会把 key 暴露给客户端");
        // 平台代理这一跳不带 Authorization：token 在 body 里，服务端不认 Bearer
        assertEquals("", authHeaders.get(0));

        JsonNode sent = objectMapper.readTree(bodies.get(0));
        assertEquals("tok-123", sent.path("token").asText());
        assertEquals("dev-456", sent.path("device_id").asText());
        assertEquals("系统提示", sent.path("system").asText());
        assertEquals("用户提示", sent.path("user").asText());
        assertEquals(0.7, sent.path("temperature").asDouble(), 1e-9);
    }

    @Test
    void 平台代理从data里取话术文本() throws Exception {
        responder = (path, body) -> new Reply(200,
                "{\"success\":true,\"data\":{\"text\":\"您好，看到贵司在招人\",\"model\":\"step-5-preview\"}}");

        AiService.AiResult r = aiService.chatPlatform(baseUrl(), "t", "d", "s", "u", 0.7);

        assertEquals("您好，看到贵司在招人", r.text());
    }

    @Test
    void 平台代理返回success为false时算失败() {
        responder = (path, body) -> new Reply(200,
                "{\"success\":false,\"error\":{\"code\":\"AI_NOT_CONFIGURED\",\"message\":\"平台未配置 AI 中转\"}}");

        AiService.AiResult r = aiService.chatPlatform(baseUrl(), "t", "d", "s", "u", 0.7);

        assertFalse(r.isOk());
        // 服务端给的 message 是给人看的，要透传，别自己编一句
        assertTrue(r.error().contains("平台未配置 AI 中转"), r.error());
    }

    @Test
    void 平台代理401说激活信息失效() {
        responder = (path, body) -> new Reply(401,
                "{\"success\":false,\"error\":{\"code\":\"TOKEN_INVALID\",\"message\":\"激活信息无效，请重新激活\"}}");

        AiService.AiResult r = aiService.chatPlatform(baseUrl(), "t", "d", "s", "u", 0.7);

        assertFalse(r.isOk());
        assertTrue(r.error().contains("重新激活"), r.error());
        assertEquals(1, hitsOf(PLATFORM_PATH), "4xx 不该重试");
    }

    @Test
    void 平台代理429会重试() {
        responder = (path, body) -> new Reply(429,
                "{\"success\":false,\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"请求过于频繁\"}}");

        AiService.AiResult r = aiService.chatPlatform(baseUrl(), "t", "d", "s", "u", 0.7);

        assertFalse(r.isOk());
        assertTrue(r.error().contains("过于频繁"), r.error());
        assertEquals(3, hitsOf(PLATFORM_PATH), "429 要按 3 次尝试打满");
    }

    @Test
    void 平台代理地址没配时直接失败不发请求() {
        AiService.AiResult r = aiService.chatPlatform("  ", "t", "d", "s", "u", 0.7);

        assertFalse(r.isOk());
        assertTrue(r.error().contains("服务端地址没配"), r.error());
        assertEquals(0, hitsOf(PLATFORM_PATH));
    }

    @Test
    void 平台代理卡密没激活时直接失败不发请求() {
        AiService.AiResult r = aiService.chatPlatform(baseUrl(), "  ", "d", "s", "u", 0.7);

        assertFalse(r.isOk());
        assertTrue(r.error().contains("卡密未激活"), r.error());
        assertEquals(0, hitsOf(PLATFORM_PATH));
    }
}
