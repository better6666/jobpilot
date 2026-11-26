package com.jobpilot.liepin;

import com.jobpilot.browser.BrowserManager;
import com.jobpilot.browser.HumanVerification;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Route;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 隔离 Chromium 回归覆盖单页无分页、跨页、空结果及登录验证。 */
public final class LiepinSearchSmoke {
    private static final String SITE = "https://www.liepin.com";
    private static final String SEARCH = "/api/com.liepin.searchfront4c.pc-search-job";

    private LiepinSearchSmoke() { }

    public static void run(Browser browser) {
        try (BrowserContext context = browser.newContext()) {
            context.route("**/*", LiepinSearchSmoke::serve);
            BrowserManager manager = mock(BrowserManager.class);
            when(manager.context()).thenReturn(context);
            LiepinDriver driver = new LiepinDriver(manager);
            List<String> logs = new ArrayList<>();
            List<String> jobs = new ArrayList<>();

            driver.processKeyword(SITE + "/zhaopin/?fixture=single", "单页", 5, logs::add, () -> false,
                    (card, page) -> jobs.add(card.getJobId() + " " + card.getJobName()));
            check(jobs.equals(List.of("101 本地测试岗位101")), "无分页的岗位应使用搜索响应中的对应信息");

            jobs.clear();
            driver.processKeyword(SITE + "/zhaopin/?fixture=multi", "多页", 5, logs::add, () -> false,
                    (card, page) -> jobs.add(card.getJobId() + " " + card.getJobName()));
            check(jobs.equals(List.of("101 本地测试岗位101", "202 本地测试岗位202")),
                    "翻页应等待新卡片，避免重复处理上一页或错配岗位");

            logs.clear();
            driver.processKeyword(SITE + "/zhaopin/?fixture=empty", "空结果", 5, logs::add, () -> false,
                    (card, page) -> { throw new AssertionError("空结果不应产生岗位"); });
            check(logs.stream().anyMatch(message -> message.contains("无匹配岗位")), "空结果应给出单独提示");

            try {
                driver.processKeyword(SITE + "/zhaopin/?fixture=login", "登录已过期", 5, logs::add, () -> false,
                        (card, page) -> { throw new AssertionError("登录页不应产生岗位"); });
                throw new AssertionError("重新登录时应暂停任务");
            } catch (HumanVerification.PausedRun e) {
                check(e.getMessage().contains("重新登录") && context.pages().getLast().url().endsWith("/login"),
                        "重新登录时应保留登录页");
            }

            AtomicBoolean stop = new AtomicBoolean();
            try {
                driver.processKeyword(SITE + "/zhaopin/?fixture=verify", "安全验证", 5, message -> {
                    logs.add(message);
                    if (message.contains("等待手动验证")) stop.set(true);
                }, stop::get, (card, page) -> { throw new AssertionError("验证弹窗未处理时不得采集底层卡片"); });
                throw new AssertionError("验证过程中收到停止，应保留网页");
            } catch (HumanVerification.PausedRun e) {
                var verification = context.pages().getLast();
                check(!verification.isClosed() && verification.getByText("请完成安全验证").isVisible(),
                        "软件不应关闭验证窗口或点击验证弹窗的关闭控件");
            }
        }
        System.out.println("PASS Liepin search: single page without pagination, fresh results after page transition, empty results and retained login/verification");
    }

    private static void serve(Route route) {
        URI target = URI.create(route.request().url());
        if (!"www.liepin.com".equals(target.getHost())) { route.abort(); return; }
        if (SEARCH.equals(target.getPath())) {
            String id = "page=2".equals(target.getQuery()) ? "202" : "101";
            String body = """
                    {"data":{"jobCardList":[{"job":{"jobId":"%s","title":"本地测试岗位%s"},
                       "comp":{"compScale":"100-499人"}}]}}
                    """.formatted(id, id);
            route.fulfill(new Route.FulfillOptions().setContentType("application/json; charset=utf-8").setBody(body));
            return;
        }
        String fixture = target.getQuery();
        if ("fixture=login".equals(fixture)) {
            route.fulfill(new Route.FulfillOptions().setStatus(302).setHeaders(Map.of("Location", SITE + "/login")));
            return;
        }
        String html = switch (fixture == null ? "" : fixture) {
            case "fixture=single" -> "<main></main>" + populate("101", false);
            case "fixture=multi" -> """
                    <main></main><ul class="list-pagination-box"><li class="ant-pagination-next">
                    <button class="ant-pagination-item-link">下一页</button></li></ul>
                    """ + populate("101", true);
            case "fixture=empty" -> "<p>没有找到相关职位</p>";
            case "fixture=verify" -> card("101") + """
                    <div class="ant-modal-wrap" role="dialog">请完成安全验证
                    <button class="ant-modal-close" onclick="this.parentNode.remove()">关闭</button></div>
                    """;
            default -> "<h1>请登录猎聘</h1>";
        };
        route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(html));
    }

    private static String populate(String id, boolean paginated) {
        String script = """
                fetch('%s?page=1').then(r => r.json()).then(() => document.querySelector('main').innerHTML = '%s');
                """.formatted(SEARCH, card(id));
        if (paginated) {
            script += """
                    document.querySelector('button').onclick = () => {
                      fetch('%s?page=2').then(r => r.json()).then(() => setTimeout(() => {
                        document.querySelector('main').innerHTML = '%s';
                        document.querySelector('button.ant-pagination-item-link').disabled = true;
                      }, 600));
                    };
                    """.formatted(SEARCH, card("202"));
        }
        return "<script>" + script + "</script>";
    }

    private static String card(String id) {
        return "<div class=jobCardPcContainer><a href=/job/" + id
                + ".shtml>列表测试岗位</a><button>聊一聊</button></div>";
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
