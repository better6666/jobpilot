package com.jobpilot.boss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.browser.BrowserManager;
import com.jobpilot.delivery.DeliveryStatus;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class BossWechatSmoke {
    private static final String BOSS = "https://www.zhipin.com";
    private static final ObjectMapper JSON = new ObjectMapper();

    private BossWechatSmoke() {}

    public static void run(Browser browser) throws Exception {
        var file = Files.createTempFile("jobpilot-wechat-", ".db");
        try (BrowserContext context = browser.newContext()) {
            var db = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + file));
            db.execute("CREATE TABLE config (config_key TEXT PRIMARY KEY, config_value TEXT, updated_at TEXT)");
            var wechat = new BossWechatExchange(db, JSON);
            var config = JSON.readValue("{\"dryRun\":false}", BossProperties.BossConfig.class);
            var card = new BossJobCard();
            card.setEncryptId("job");
            card.setEncryptUserId("hr");
            check(wechat.request(null, card, config, () -> false) == null, "旧设置默认关闭");
            config.setRequestWechat(true);
            config.setDryRun(true);
            check(wechat.request(null, card, config, () -> false) == null, "预演不触碰聊天页");
            config.setDryRun(false);
            check(wechat.request(null, card, config, () -> true) == null, "停止后不触碰聊天页");

            var writes = new ArrayList<String>();
            context.route("**/*", route -> serve(route, writes));
            Page page = context.newPage();
            try {
                for (String mode : List.of("disabled", "wrong-job", "manual-input", "accept", "wrong-recipient")) {
                    page.navigate(BOSS + "/web/geek/chat?" + mode);
                    check(!wechat.request(page, card, config, () -> false).contains("已发出"), mode);
                    check(writes.isEmpty(), mode + " 不能提交任何交换请求");
                }
            } finally {
                page.close();
            }

            BrowserManager manager = new BrowserManager(new BrowserManager.BrowserProperties()) {
                @Override public BrowserContext context() { return context; }
            };
            var driver = new BossDriver(manager, null, wechat);
            Page list = context.newPage();
            list.navigate(BOSS + "/test-list");
            var delivered = driver.deliver(list, card, "本地模拟问候", config, message -> {}, () -> false);
            check(delivered.status() == DeliveryStatus.DELIVERED && delivered.failReason().contains("已发出"),
                    "正常投递通过原生聊天控件申请交换微信");
            check(writes.equals(List.of("/test-greeting", "type=2&securityId=expected")), "先发问候，再发一次申请");

            page = context.newPage();
            page.navigate(BOSS + "/web/geek/chat?ready");
            var restarted = new BossWechatExchange(db, JSON);
            check(restarted.request(page, card, config, () -> false).contains("已有微信申请记录"), "持久保存会话去重");
            page.navigate(BOSS + "/web/geek/chat?uncertain");
            check(restarted.request(page, card, config, () -> false).contains("未确认"), "缺少回执不能记为成功");
            check(restarted.request(page, card, config, () -> false).contains("已有微信申请记录"), "请求回执不明也不重发");
            check(writes.equals(List.of("/test-greeting", "type=2&securityId=expected", "type=2&securityId=expected")),
                    "不同账号可单独申请，每个会话只提交一次");
        } finally {
            Files.deleteIfExists(file);
        }
        System.out.println("PASS BOSS WeChat: opt-in, preview, platform readiness, recipient guard, no inbound accept, receipt and persisted dedup");
    }

    private static void serve(Route route, List<String> writes) {
        String url = route.request().url();
        String contentType = "text/html; charset=utf-8";
        String html;
        if (url.endsWith("/test-list")) {
            html = "<a class=more-job-btn href=/job_detail/job.html>查看更多</a>";
        } else if (url.endsWith("/job_detail/job.html")) {
            html = "<a class=btn-startchat href=/web/geek/chat?ready>立即沟通</a>";
        } else if (url.endsWith("/test-greeting")) {
            writes.add("/test-greeting");
            html = "ok";
        } else if (url.startsWith(BOSS + "/web/geek/chat?")) {
            String mode = url.substring(url.lastIndexOf('?') + 1);
            String endpoint = mode.equals("accept") ? "accept" : "request";
            String securityId = mode.equals("wrong-recipient") ? "wrong" : "expected";
            html = """
                    <textarea class="input-area" onkeydown="if(event.key==='Enter'){event.preventDefault();fetch('/test-greeting');this.value=''}"></textarea>
                    <div id="wechat-toolbar">
                      <div class="btn-weixin %s" d-c="62011">换微信</div>
                      <div class="panel-wechat" hidden>
                        <p class="title">确认与对方交换微信吗？</p>%s
                        <span class="btn-sure-v2">确定</span>
                      </div>
                    </div>
                    <script>
                      window._PAGE = { uid: %s };
                      window._reportData = { reportedId: 'hr', targetId: '%s' };
                      const toolbar = document.querySelector('#wechat-toolbar');
                      toolbar.__vue__ = { $options: { name: 'WeChatBtn' }, conversation$: { securityId: 'expected' } };
                      toolbar.querySelector('.btn-weixin').onclick = () => toolbar.querySelector('.panel-wechat').hidden = false;
                      toolbar.querySelector('.btn-sure-v2').onclick = () => fetch('/wapi/zpchat/exchange/%s%s', {
                        method: 'POST', body: 'type=2&securityId=%s'
                      }).catch(() => {});
                    </script>
                    """.formatted(mode.equals("disabled") ? "unable" : "", mode.equals("manual-input") ? "<input>" : "",
                    mode.equals("uncertain") ? "84" : "42", mode.equals("wrong-job") ? "other" : "job",
                    endpoint, mode.equals("uncertain") ? "?uncertain" : "", securityId);
        } else if (url.startsWith(BOSS + "/wapi/zpchat/exchange/")) {
            writes.add(route.request().postData());
            contentType = "application/json";
            html = url.endsWith("?uncertain") ? "{}" : "{\"code\":0,\"zpData\":{\"status\":0}}";
        } else {
            route.abort();
            return;
        }
        route.fulfill(new Route.FulfillOptions().setContentType(contentType).setBody(html));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
