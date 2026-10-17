package com.jobpilot.lagou;

import com.jobpilot.browser.BrowserManager;
import com.jobpilot.browser.HumanVerification;
import com.jobpilot.browser.PageProbe;
import com.jobpilot.delivery.CardConsumer;
import com.jobpilot.delivery.DeliveryOutcome;
import com.jobpilot.delivery.DeliveryStatus;
import com.jobpilot.delivery.LoginResult;
import com.jobpilot.delivery.ProgressListener;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.BooleanSupplier;

/**
 * 拉勾浏览器驱动：登录 → 逐页读 {@code __NEXT_DATA__} → 详情页点"投简历"。
 *
 * <p>2026-10-17 只读实测：
 * <ul>
 *   <li>搜索页数据全在 {@code script#__NEXT_DATA__}，每页 15 条，{@code pn} 翻页</li>
 *   <li>列表卡片没有链接，详情页是 {@code /wn/jobs/{positionId}.html}，投递按钮 {@code a.btn_apply}"投简历"</li>
 *   <li>未登录时页头和详情页都有 {@code a.login}"登录"</li>
 *   <li>匿名访问会被整页换成阿里滑块（标题"滑动验证页面"），交给 {@link HumanVerification}</li>
 * </ul>
 * <p><b>"投简历"之后的流程没实测</b>（真投要用户自己发起），处理方式同 58：判不出成功记失败，
 * 每次把弹层写日志待校准。
 */
@Slf4j
@Component
public class LagouDriver {

    private static final String DISPLAY_NAME = "拉勾";
    private static final String HOME = "https://www.lagou.com/";
    private static final String LOGIN_URL = "https://passport.lagou.com/login/login.html";
    private static final String APPLY = "a.btn_apply";
    private static final String[] LIMIT = {"今日投递已达上限", "投递次数已达上限", "今天投递太多", "达到上限"};
    private static final int PAGE_SIZE = 15;
    private static final int MAX_PAGE = 30;
    private static final int NAV_TIMEOUT_MS = 45_000;
    private static final int ACTION_TIMEOUT_MS = 10_000;

    private final BrowserManager browserManager;
    private volatile int waitMinutes = 5;

    public LagouDriver(BrowserManager browserManager) {
        this.browserManager = browserManager;
    }

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------

    public LoginResult ensureLogin(int timeoutMinutes, ProgressListener listener, BooleanSupplier stop) {
        waitMinutes = timeoutMinutes;
        Page page = browserManager.context().newPage();
        try {
            navigate(page, HOME, listener);
            if (!HumanVerification.waitIfShowing(page, DISPLAY_NAME, timeoutMinutes, listener, stop)) {
                return LoginResult.TIMEOUT;
            }
            sleep(1500);
            if (isLoggedIn(page)) {
                listener.onProgress("已检测到拉勾登录态");
                return LoginResult.LOGGED_IN;
            }
            navigate(page, LOGIN_URL, listener);
            listener.onProgress("未登录拉勾，请在浏览器窗口里登录（扫码或验证码，等待 " + timeoutMinutes + " 分钟）");
            long deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L;
            while (System.currentTimeMillis() < deadline && !stop.getAsBoolean()) {
                sleep(2500);
                String url = page.url();
                if (url != null && !url.contains("passport.lagou.com")) {
                    navigate(page, HOME, listener);
                    sleep(1500);
                    if (isLoggedIn(page)) {
                        listener.onProgress("登录成功");
                        return LoginResult.LOGGED_IN;
                    }
                }
            }
            return LoginResult.TIMEOUT;
        } finally {
            closeQuietly(page);
        }
    }

    /** 页头没有可见的"登录"链接就算登录了 */
    boolean isLoggedIn(Page page) {
        try {
            Object out = page.evaluate("() => ![...document.querySelectorAll('a.login, a')]"
                    + ".some(a => a.offsetParent !== null && (a.innerText || '').trim() === '登录')");
            return Boolean.TRUE.equals(out);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 采集
    // ------------------------------------------------------------------

    public void processKeyword(String city, String keyword, String myDegree, int maxCards,
                               ProgressListener listener, BooleanSupplier stop, CardConsumer<LagouJobCard> consumer) {
        Page page = browserManager.context().newPage();
        page.onPopup(LagouDriver::closeQuietly);
        try {
            int processed = 0;
            int pages = MAX_PAGE;
            for (int pn = 1; pn <= pages; pn++) {
                if (stop.getAsBoolean()) {
                    listener.onProgress("收到停止指令，中断采集（已处理 " + processed + " 个）");
                    break;
                }
                navigate(page, LagouSearchUrl.build(city, keyword, myDegree, pn), listener);
                if (!HumanVerification.waitIfShowing(page, DISPLAY_NAME, waitMinutes, listener, stop)) {
                    return;
                }
                LagouJobCard.Page data = LagouJobCard.parse(readNextData(page));
                if (pn == 1) {
                    pages = Math.min(MAX_PAGE, Math.max(1, (data.total() + PAGE_SIZE - 1) / PAGE_SIZE));
                    if (myDegree != null && LagouSearchUrl.build(city, keyword, myDegree, 1).contains("&xl=")) {
                        listener.onProgress("【" + keyword + "】只看要求大专的岗位（拉勾的学历筛选只能单选）");
                    }
                }
                if (data.cards().isEmpty()) {
                    listener.onProgress("【" + keyword + "】" + (pn == 1 ? "没有搜到岗位" : "没有更多岗位"));
                    break;
                }
                listener.onProgress("【" + keyword + "】第 " + pn + " 页 " + data.cards().size() + " 个岗位（共 " + data.total() + " 个）");
                for (LagouJobCard card : data.cards()) {
                    if (stop.getAsBoolean() || processed >= maxCards) {
                        break;
                    }
                    processed++;
                    try {
                        consumer.accept(card, page);
                    } catch (Exception e) {
                        log.debug("【{}】处理 {} 异常: {}", keyword, card.getJobId(), e.getMessage());
                    }
                }
                if (processed >= maxCards) {
                    listener.onProgress("【" + keyword + "】已达单关键词上限 " + maxCards + " 个");
                    break;
                }
            }
            listener.onProgress("【" + keyword + "】处理完成，共 " + processed + " 个");
        } finally {
            closeQuietly(page);
        }
    }

    private String readNextData(Page page) {
        for (int i = 0; i < 16; i++) {
            try {
                Object o = page.evaluate("() => { const el = document.getElementById('__NEXT_DATA__');"
                        + " return el ? el.textContent : null; }");
                if (o != null) {
                    return String.valueOf(o);
                }
            } catch (Exception ignore) {
            }
            sleep(500);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 投递
    // ------------------------------------------------------------------

    public DeliveryOutcome deliver(LagouJobCard card, LagouProperties.LagouConfig config,
                                   ProgressListener listener, BooleanSupplier stop) {
        String target = (card.getBrandName() != null ? card.getBrandName() : "?")
                + " | " + (card.getJobName() != null ? card.getJobName() : "?");
        if (config.isDryRun()) {
            listener.onProgress("预演：跳过真实投递 | " + target);
            return DeliveryOutcome.preview(null);
        }
        if (stop.getAsBoolean()) {
            return DeliveryOutcome.failed("已停止");
        }
        Page detail = browserManager.context().newPage();
        detail.onPopup(LagouDriver::closeQuietly);
        try {
            navigate(detail, card.getJobUrl(), listener);
            if (!HumanVerification.waitIfShowing(detail, DISPLAY_NAME, config.getLoginTimeoutMinutes(), listener, stop)) {
                return DeliveryOutcome.failed("人机验证未完成");
            }
            Locator btn = null;
            for (int i = 0; i < 16 && btn == null; i++) {
                Locator b = detail.locator(APPLY).first();
                if (b.count() > 0 && b.isVisible()) {
                    btn = b;
                } else {
                    sleep(400);
                }
            }
            if (btn == null) {
                return DeliveryOutcome.failed("详情页没有投简历按钮（岗位可能已下线）");
            }
            String before = safeText(btn);
            if (before != null && (before.contains("已投递") || before.contains("已投"))) {
                return new DeliveryOutcome(DeliveryStatus.DELIVERED, null, null);
            }
            if (!isLoggedIn(detail)) {
                return DeliveryOutcome.failed("拉勾未登录，投不了");
            }
            btn.click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
            boolean confirmed = false;
            String lastShape = null;
            for (int i = 0; i < 25 && !stop.getAsBoolean(); i++) {
                sleep(400);
                String shape = PageProbe.overlayShape(detail);
                if (shape != null && !shape.equals(lastShape)) {
                    log.info("拉勾投简历后页面弹出 | {} | {}", target, shape);
                    lastShape = shape;
                }
                String limit = PageProbe.bodyHit(detail, LIMIT);
                if (limit != null) {
                    return DeliveryOutcome.limit("拉勾提示：" + limit);
                }
                String after = safeText(detail.locator(APPLY).first());
                if ((after != null && (after.contains("已投递") || after.contains("已投")))
                        || PageProbe.bodyHit(detail, "投递成功", "简历已投递") != null) {
                    listener.onProgress("已投递 | " + target);
                    return DeliveryOutcome.delivered(null);
                }
                if (!confirmed && PageProbe.clickConfirm(detail)) {
                    confirmed = true;
                    log.info("拉勾投递：点了弹窗里的确认按钮 | {}", target);
                }
            }
            return DeliveryOutcome.failed("未确认投递结果");
        } catch (Exception e) {
            log.warn("拉勾投递异常 | {}: {}", target, e.getMessage());
            return DeliveryOutcome.failed(e.getMessage());
        } finally {
            closeQuietly(detail);
        }
    }

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    private void navigate(Page page, String url, ProgressListener listener) {
        try {
            page.setDefaultTimeout(ACTION_TIMEOUT_MS);
            page.navigate(url, new Page.NavigateOptions().setTimeout(NAV_TIMEOUT_MS)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        } catch (Exception e) {
            log.debug("导航到 {} 超时（{}）", url, e.getMessage());
            if (listener != null) {
                listener.onProgress("页面打开较慢，继续等待…");
            }
        }
    }

    private static String safeText(Locator l) {
        try {
            return l.count() == 0 ? null : l.innerText(new Locator.InnerTextOptions().setTimeout(ACTION_TIMEOUT_MS)).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Page page) {
        try {
            page.close();
        } catch (Exception ignore) {
        }
    }
}
