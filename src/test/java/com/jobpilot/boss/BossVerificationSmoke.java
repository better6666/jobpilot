package com.jobpilot.boss;

import com.jobpilot.browser.BrowserManager;
import com.jobpilot.browser.HumanVerification;
import com.jobpilot.delivery.DeliveryStatus;
import com.jobpilot.delivery.LoginResult;
import com.jobpilot.delivery.ProgressListener;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.Cookie;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 在隔离的合成页面验证任务的人工等待，不访问真实招聘网站。 */
public final class BossVerificationSmoke {
    private static final String BOSS = "https://www.zhipin.com";

    private BossVerificationSmoke() { }

    public static void verifyPersistentBrowser(Playwright playwright, Path executable) throws Exception {
        Path profile = Files.createTempDirectory("jobpilot-test-profile-");
        try (BrowserContext context = playwright.chromium().launchPersistentContext(profile,
                new BrowserType.LaunchPersistentContextOptions().setExecutablePath(executable).setHeadless(true))) {
            AtomicBoolean verified = new AtomicBoolean();
            context.route("**/*", route -> {
                String html = "<p>岗位列表</p>";
                if (route.request().url().endsWith("/manual-test")) verified.set(true);
                else if (!verified.get()) {
                    html = "<div role=dialog>验证失败<button onclick=\"fetch('/manual-test').then(()=>document.body.textContent='页面已恢复')\">手动处理测试按钮</button></div>";
                }
                route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(html));
            });
            Page anchor = context.pages().getFirst();
            BrowserManager manager = new BrowserManager(new BrowserManager.BrowserProperties()) {
                @Override public BrowserContext context() { return context; }
            };
            BossDriver driver = new BossDriver(manager);
            AtomicBoolean stop = new AtomicBoolean();
            try {
                driver.ensureLogin(1, message -> {
                    if (message.contains("等待手动验证")) stop.set(true);
                }, stop::get);
                throw new AssertionError("验证未完成时暂停应保留浏览器页面");
            } catch (HumanVerification.PausedRun e) {
                check(e.getMessage().contains("页面已保留"), "主动停止后应告知如何继续");
            }
            Page retained = context.pages().stream().filter(page -> page != anchor).findFirst().orElseThrow();
            retained.getByText("手动处理测试按钮").click();
            retained.getByText("页面已恢复").waitFor();
            context.addCookies(List.of(new Cookie("bst", "test-login").setUrl(BOSS)));
            check(driver.ensureLogin(1, message -> {}, () -> false) == LoginResult.LOGGED_IN,
                    "客户处理后应可在原浏览器资料里重启投递，无需退出应用");
            Page background = BrowserManager.newBackgroundPage(context);
            background.navigate(BOSS + "/web/geek/jobs");
            check(background.context() == context && context.pages().contains(anchor)
                    && String.valueOf(background.evaluate("document.cookie")).contains("bst=test-login"),
                    "后台标签应复用持久化浏览器登录态且保留原标签页");
            background.close();
            check(!anchor.isClosed(), "关闭后台标签不应关闭浏览器");
        } finally {
            try (var paths = Files.walk(profile)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("PASS BOSS: bundled Chromium, retained failed verification, manual recovery and background session reuse");
    }

    public static void run(Browser browser) {
        try (BrowserContext context = browser.newContext()) {
            AtomicInteger contacts = new AtomicInteger();
            AtomicInteger sends = new AtomicInteger();
            context.route("**/*", route -> serve(route, contacts, sends));
            BrowserManager manager = new BrowserManager(new BrowserManager.BrowserProperties()) {
                @Override public BrowserContext context() { return context; }
            };
            BossDriver driver = new BossDriver(manager);
            Page list = context.newPage();
            list.navigate(BOSS + "/list");
            BossJobCard card = new BossJobCard();
            card.setEncryptId("challenge");
            card.setBrandName("本地模拟企业");
            card.setJobName("本地测试岗位");
            var events = new ArrayList<String>();
            AtomicBoolean requested = new AtomicBoolean();
            ProgressListener progress = new ProgressListener() {
                @Override public void onProgress(String message) { events.add(message); }
                @Override public void waitingForBrowser(boolean waiting) { requested.set(waiting); }
                @Override public boolean takeBrowserRequest() { return requested.getAndSet(false); }
            };
            var first = driver.deliver(list, card, "本地测试消息", false, progress, () -> false);
            check(first.status() == DeliveryStatus.UNCONFIRMED, "验证打断沟通应列为待核对");
            check(contacts.get() == 1 && sends.get() == 0, "验证后不得重复沟通或发送消息");
            check(events.stream().anyMatch(m -> m.contains("等待手动验证")) && events.stream().anyMatch(m -> m.contains("等待结束")),
                    "实际弹出的新标签页应暂停并等待页面恢复");
            list.locator("a").evaluate("el => el.href = '/job_detail/applied.html'");
            card.setEncryptId("applied");
            var applied = driver.deliver(list, card, "本地测试消息", false, progress, () -> false);
            check(applied.status() == DeliveryStatus.ALREADY_APPLIED && contacts.get() == 1,
                    "招聘网站已有沟通记录时不得点击继续沟通");

            list.locator("a").evaluate("el => el.href = '/job_detail/ready.html'");
            var mismatch = driver.deliver(list, card, "本地测试消息", false, progress, () -> false);
            check(mismatch.status() == DeliveryStatus.FAILED && sends.get() == 0, "不得投递旧搜索卡片残留的详情链接");
            card.setEncryptId("ready");
            var next = driver.deliver(list, card, "本地测试消息", false, events::add, () -> false);
            check(next.status() == DeliveryStatus.DELIVERED && sends.get() == 1, "验证完成后后续正常岗位继续运行");

            Page restricted = context.newPage();
            restricted.navigate(BOSS + "/restricted");
            try {
                HumanVerification.waitForUser(() -> java.util.List.of(restricted), "BOSS", events::add, () -> false);
                throw new AssertionError("账号限制应该停止任务");
            } catch (HumanVerification.PausedRun e) {
                check(e.getMessage().contains("账号异常") && !restricted.isClosed(), "账号限制状态保持可查看");
            }
        }
        System.out.println("PASS BOSS: background tabs, manual verification, cooldown, skip existing chat, identity guard, next job, account restriction");
    }

    private static void serve(Route route, AtomicInteger contacts, AtomicInteger sends) {
        String url = route.request().url();
        String html;
        if (url.endsWith("/list")) {
            html = "<a class=more-job-btn href=/job_detail/challenge.html>查看更多</a>";
        } else if (url.endsWith("/job_detail/challenge.html")) {
            html = "<a class=btn-startchat href=# onclick=\"fetch('/record-contact');window.open('/captcha','manual-check');return false\">立即沟通</a>";
        } else if (url.endsWith("/captcha")) {
            // 合成页面在几秒后模拟用户已经完成手动操作后的状态。
            html = "<div role=dialog>请完成安全验证</div><script>setTimeout(()=>{history.replaceState({},'', '/test-complete');document.body.textContent='页面已恢复'},3000)</script>";
        } else if (url.endsWith("/job_detail/applied.html")) {
            html = "<a class=btn-startchat href=# onclick=\"fetch('/record-contact');return false\">继续沟通</a>";
        } else if (url.endsWith("/job_detail/ready.html")) {
            html = "<a class=btn-startchat href=# onclick=\"document.querySelector('[role=dialog]').hidden=false;return false\">立即沟通</a>"
                    + "<div role=dialog hidden>已向 BOSS 发送消息<button onclick=\"this.parentElement.hidden=true;document.querySelector('textarea').hidden=false\">继续沟通</button></div>"
                    + "<textarea hidden class=input-area onkeydown=\"if(event.key==='Enter'){event.preventDefault();fetch('/record-send');this.value=''}\"></textarea>";
        } else if (url.endsWith("/record-contact")) {
            contacts.incrementAndGet(); html = "ok";
        } else if (url.endsWith("/record-send")) {
            sends.incrementAndGet(); html = "ok";
        } else if (url.endsWith("/restricted")) {
            html = "<div role=dialog>账号异常，操作受限</div>";
        } else {
            route.abort(); return;
        }
        route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(html));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
