package com.jobpilot.boss;

import com.jobpilot.browser.BrowserManager;
import com.jobpilot.browser.HumanVerification;
import com.jobpilot.delivery.DeliveryStatus;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 在隔离的合成页面验证任务的人工等待，不访问真实招聘网站。 */
public final class BossVerificationSmoke {
    private static final String BOSS = "https://www.zhipin.com";

    private BossVerificationSmoke() { }

    public static void run(Browser browser) {
        try (BrowserContext context = browser.newContext()) {
            AtomicInteger contacts = new AtomicInteger();
            AtomicInteger sends = new AtomicInteger();
            context.route("**/*", route -> serve(route, contacts, sends));
            BrowserManager manager = mock(BrowserManager.class);
            when(manager.context()).thenReturn(context);
            BossDriver driver = new BossDriver(manager);
            Page list = context.newPage();
            list.navigate(BOSS + "/list");
            BossJobCard card = new BossJobCard();
            card.setBrandName("本地模拟企业");
            card.setJobName("本地测试岗位");
            var events = new ArrayList<String>();
            var first = driver.deliver(list, card, "本地测试消息", false, events::add, () -> false);
            check(first.status() == DeliveryStatus.UNCONFIRMED, "验证打断沟通应列为待核对");
            check(contacts.get() == 1 && sends.get() == 0, "验证后不得重复沟通或发送消息");
            check(events.stream().anyMatch(m -> m.contains("手动完成验证")) && events.stream().anyMatch(m -> m.contains("等待结束")),
                    "实际弹出的新标签页应暂停并等待页面恢复");
            list.locator("a").evaluate("el => el.href = '/job_detail/ready'");
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
        System.out.println("PASS BOSS verification: popup retained, manual state change, cooldown, no repeat, next job, account restriction");
    }

    private static void serve(Route route, AtomicInteger contacts, AtomicInteger sends) {
        String url = route.request().url();
        String html;
        if (url.endsWith("/list")) {
            html = "<a class=more-job-btn href=/job_detail/challenge>查看更多</a>";
        } else if (url.endsWith("/job_detail/challenge")) {
            html = "<a class=btn-startchat href=# onclick=\"fetch('/record-contact');window.open('/captcha','manual-check');return false\">立即沟通</a>";
        } else if (url.endsWith("/captcha")) {
            // 合成页面在几秒后模拟用户已经完成手动操作后的状态。
            html = "<div role=dialog>请完成安全验证</div><script>setTimeout(()=>{history.replaceState({},'', '/test-complete');document.body.textContent='页面已恢复'},3000)</script>";
        } else if (url.endsWith("/job_detail/ready")) {
            html = "<a class=btn-startchat href=# onclick=\"document.querySelector('textarea').hidden=false;return false\">立即沟通</a>"
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
