package com.jobpilot.boss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Component
public class BossWechatExchange {
    // BOSS 网页版 chat-new/v5580 的原生按钮、确认弹层和回执。
    private static final String TOOLBAR = ".btn-weixin[d-c='62011']:visible";
    private static final Pattern EXCHANGE = Pattern.compile(
            "https://www\\.zhipin\\.com/wapi/zpchat/exchange/(request|accept)(?:\\?.*)?");
    private static final String REQUEST_PATH = "/wapi/zpchat/exchange/request";
    private static final int ACTION_TIMEOUT_MS = 4_000;
    private final JdbcTemplate database;
    private final ObjectMapper json;

    public BossWechatExchange(JdbcTemplate database, ObjectMapper json) {
        this.database = database;
        this.json = json;
    }

    String request(Page page, BossJobCard card, BossProperties.BossConfig config, BooleanSupplier stop) {
        if (!config.isRequestWechat() || config.isDryRun() || stop.getAsBoolean()) return null;
        try {
            URI url = URI.create(page.url());
            if (!"https://www.zhipin.com".equals(url.getScheme() + "://" + url.getAuthority())
                    || !"/web/geek/chat".equals(url.getPath())) {
                return "未进入 BOSS 聊天页，已跳过微信申请";
            }
            Locator toolbar = page.locator(TOOLBAR);
            if (toolbar.count() != 1 || toolbar.getAttribute("class").contains("unable")
                    || "true".equals(toolbar.getAttribute("aria-disabled"))) {
                return "平台暂未开放换微信或已有申请，已跳过";
            }
            Conversation conversation = conversation(toolbar, card);
            if (conversation == null) return "无法核对当前 BOSS 会话，已跳过微信申请";
            String key = "boss.wechat." + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((conversation.account() + "\n" + card.getBossId()).getBytes(StandardCharsets.UTF_8)));
            if (database.queryForObject("SELECT COUNT(*) FROM config WHERE config_key = ?", Integer.class, key) > 0) {
                return "该 BOSS 会话已有微信申请记录，已跳过";
            }
            return requestThroughChat(page, toolbar, card, conversation, key, stop);
        } catch (Exception ignored) {
            return "微信申请结果未确认，请在 BOSS 聊天中核对";
        }
    }

    private String requestThroughChat(Page page, Locator toolbar, BossJobCard card, Conversation conversation,
                                      String key, BooleanSupplier stop) {
        AtomicBoolean tried = new AtomicBoolean();
        AtomicBoolean sent = new AtomicBoolean();
        AtomicBoolean blocked = new AtomicBoolean();
        AtomicBoolean confirming = new AtomicBoolean();
        Consumer<Route> guard = route -> {
            if (!confirming.get() || stop.getAsBoolean() || tried.get()
                    || !matchesRequest(route.request().url(), route.request().method(),
                    route.request().postData(), conversation.securityId())) {
                blocked.set(true);
                route.abort();
                return;
            }
            // 先保留尝试记录，网络超时或应用中断后也不会再发送一次。
            int inserted;
            try {
                inserted = database.update("INSERT OR IGNORE INTO config (config_key, config_value, updated_at) VALUES (?, ?, ?)",
                        key, "attempted", Instant.now().toString());
            } catch (RuntimeException e) {
                inserted = 0;
            }
            if (inserted == 0) {
                blocked.set(true);
                route.abort();
                return;
            }
            tried.set(true);
            route.fallback();
        };
        Consumer<Response> receipt = response -> {
            if (!tried.get() || !EXCHANGE.matcher(response.url()).matches()
                    || !matchesRequest(response.url(), response.request().method(),
                    response.request().postData(), conversation.securityId())) return;
            try {
                var body = json.readTree(response.text());
                sent.set(response.ok() && body.path("code").asInt(-1) == 0
                        && body.path("zpData").path("status").asInt(-1) == 0);
            } catch (Exception ignored) {
                // 留待用户在平台核对，同一会话不会自动重发。
            }
        };

        page.route(EXCHANGE, guard);
        page.onResponse(receipt);
        try {
            if (stop.getAsBoolean()) return null;
            toolbar.click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
            Locator dialog = toolbar.locator("xpath=..").locator(".panel-wechat:visible");
            long deadline = System.nanoTime() + ACTION_TIMEOUT_MS * 1_000_000L;
            while (dialog.count() == 0 && !blocked.get() && !stop.getAsBoolean() && System.nanoTime() < deadline) {
                page.waitForTimeout(100);
            }
            Locator confirm = dialog.locator(".btn-sure-v2:text-is('确定'):visible");
            if (blocked.get() || stop.getAsBoolean() || dialog.count() != 1 || confirm.count() != 1
                    || dialog.locator(".title:text-is('确认与对方交换微信吗？'):visible").count() != 1
                    || dialog.locator("input, .secure-exchange").count() > 0
                    || !conversation.equals(conversation(toolbar, card))) {
                return "平台未提供标准换微信确认，请在 BOSS 聊天中查看";
            }
            confirming.set(true);
            confirm.click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
            deadline = System.nanoTime() + ACTION_TIMEOUT_MS * 1_000_000L;
            while (!sent.get() && !blocked.get() && !stop.getAsBoolean() && System.nanoTime() < deadline) {
                page.waitForTimeout(100);
            }
            if (sent.get()) return "已发出 BOSS 交换微信申请，等待对方确认";
            if (tried.get()) return "微信申请结果未确认，请在 BOSS 聊天中核对";
            return "未发出微信申请，请在 BOSS 聊天中查看平台提示";
        } finally {
            page.offResponse(receipt);
            page.unroute(EXCHANGE, guard);
        }
    }

    private static Conversation conversation(Locator toolbar, BossJobCard card) {
        if (card.getBossId() == null || card.getJobId() == null) return null;
        Object data = toolbar.evaluate("""
                (button, ids) => {
                  const component = button.parentElement?.__vue__;
                  const active = window._reportData;
                  const account = String(window._PAGE?.uid ?? '');
                  if (component?.$options?.name !== 'WeChatBtn' || !/^\\d+$/.test(account)
                      || active?.reportedId !== ids.recruiter || active?.targetId !== ids.job) return null;
                  const chat = component.conversation$;
                  if (!chat || chat.requestWeiXin || typeof chat.securityId !== 'string' || !chat.securityId) return null;
                  return { account, securityId: chat.securityId };
                }
                """, Map.of("recruiter", card.getBossId(), "job", card.getJobId()));
        if (!(data instanceof Map<?, ?> values)) return null;
        return new Conversation((String) values.get("account"), (String) values.get("securityId"));
    }

    private static boolean matchesRequest(String url, String method, String body, String securityId) {
        if (!"POST".equals(method) || !REQUEST_PATH.equals(URI.create(url).getPath()) || body == null) return false;
        Map<String, String> fields = new HashMap<>();
        try {
            for (String field : body.split("&")) {
                String[] pair = field.split("=", 2);
                if (pair.length != 2) return false;
                if (fields.putIfAbsent(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) != null) return false;
            }
            return "2".equals(fields.get("type")) && securityId.equals(fields.get("securityId"));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private record Conversation(String account, String securityId) {}
}
