package com.jobpilot.yingjiesheng;

import com.jobpilot.browser.BrowserManager;
import com.jobpilot.browser.HumanVerification;
import com.jobpilot.delivery.CardConsumer;
import com.jobpilot.delivery.DeliveryOutcome;
import com.jobpilot.delivery.DeliveryStatus;
import com.jobpilot.delivery.LoginResult;
import com.jobpilot.delivery.ProgressListener;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * 应届生求职网（q.yingjiesheng.com）浏览器驱动。
 *
 * <p>2026-10-14 实测：
 * <ul>
 *   <li>岗位实体来自 {@code /open/noauth/job/search} 接口，请求头带页面算的
 *       {@code sign}，<b>不能自己拼请求</b>，只能让页面发、我们在 onResponse 里拦</li>
 *   <li>搜索页地址是 {@code /jobs/search/关键词}，城市不进地址：点筛选栏
 *       「工作地点」那一排的城市名，接口才会带上 {@code jobarea=010000}</li>
 *   <li>列表项 {@code .search-list-item-wrapper}，jobId 在
 *       {@code a.search-list-href} 的 {@code /jobdetail/123.html} 里；
 *       「立即申请」是列表项里的 {@code div.notdelivery}</li>
 *   <li><b>未登录只给 20 条</b>，底下是「登录后查看更多热招职位」，没有分页</li>
 *   <li>连开几页就弹阿里滑块（标题「滑动验证页面」），见 {@link HumanVerification}</li>
 * </ul>
 */
@Slf4j
@Component
public class YingjieshengDriver {

    private static final String HOME_URL = "https://q.yingjiesheng.com/";
    private static final String SEARCH_BASE = "https://q.yingjiesheng.com/jobs/search/";
    private static final String SEARCH_API = "/open/noauth/job/search?";
    private static final String DISPLAY_NAME = "应届生求职网";

    private static final String ITEM = ".search-list-item-wrapper";
    private static final String ITEM_LINK = "a.search-list-href";
    private static final String APPLY = ".notdelivery";
    private static final String DIALOG = ".el-dialog, .el-message-box, [role='dialog']";
    private static final String NEXT_PAGE = ".el-pagination button.btn-next:not([disabled]), "
            + "[class*='pagination'] :is(a, button, li):has-text('下一页')";

    private static final String[] LIMIT_TEXTS = {"今日投递太多", "申请次数已达上限", "达到上限", "休息一下明天再来"};

    private static final int NAV_TIMEOUT_MS = 45_000;
    private static final int ACTION_TIMEOUT_MS = 10_000;
    private static final int RESPONSE_TIMEOUT_MS = 15_000;
    private static final int MAX_PAGE = 30;

    private final BrowserManager browserManager;
    private volatile int waitMinutes = 5;

    public YingjieshengDriver(BrowserManager browserManager) {
        this.browserManager = browserManager;
    }

    static String searchUrl(String keyword) {
        // 路径段里的空格要是 %20，URLEncoder 给的是 +
        return SEARCH_BASE + URLEncoder.encode(keyword == null ? "" : keyword.trim(), StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------

    public LoginResult ensureLogin(int timeoutMinutes, ProgressListener listener, BooleanSupplier stop) {
        waitMinutes = timeoutMinutes;
        Page page = browserManager.context().newPage();
        configureTimeouts(page);
        try {
            navigate(page, HOME_URL, listener);
            if (!HumanVerification.waitIfShowing(page, DISPLAY_NAME, timeoutMinutes, listener, stop)) {
                return LoginResult.TIMEOUT;
            }
            // 导航栏是前端渲染的，进页面立刻看必然是"未登录"，先给几秒
            for (int i = 0; i < 12 && !stop.getAsBoolean(); i++) {
                if (isLoggedIn(page)) {
                    listener.onProgress("已检测到应届生求职网登录态");
                    return LoginResult.LOGGED_IN;
                }
                sleep(500);
            }
            listener.onProgress("未登录应届生求职网，请在浏览器窗口右上角点「登录/注册」（微信扫码或手机验证码，等待 "
                    + timeoutMinutes + " 分钟）。未登录每个关键词只能看到 20 个岗位，也投不了");
            long deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L;
            while (System.currentTimeMillis() < deadline && !stop.getAsBoolean()) {
                sleep(2000);
                if (isLoggedIn(page)) {
                    listener.onProgress("登录成功");
                    return LoginResult.LOGGED_IN;
                }
            }
            return LoginResult.TIMEOUT;
        } finally {
            closeQuietly(page);
        }
    }

    /** 导航栏右上角还挂着「登录/注册」就是没登录 */
    boolean isLoggedIn(Page page) {
        try {
            Object out = page.evaluate("() => {"
                    + "  const nodes = [...document.querySelectorAll('a, span, div, button')];"
                    + "  const hit = nodes.find(n => n.children.length === 0"
                    + "      && n.textContent.trim() === '登录/注册' && n.offsetParent !== null);"
                    + "  return !hit && document.body && document.body.innerText.length > 50;"
                    + "}");
            return Boolean.TRUE.equals(out);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 采集
    // ------------------------------------------------------------------

    public void processKeyword(String searchUrl, String keyword, String city, int maxCards,
                               ProgressListener listener, BooleanSupplier stop,
                               CardConsumer<YingjieshengJobCard> consumer) {
        Page page = browserManager.context().newPage();
        page.onPopup(YingjieshengDriver::closeQuietly);
        configureTimeouts(page);
        AtomicReference<Map<String, YingjieshengJobCard>> cache = new AtomicReference<>(new HashMap<>());
        AtomicLong seq = new AtomicLong();
        page.onResponse(response -> {
            if (isSearchResponse(response)) {
                Map<String, YingjieshengJobCard> byId = new HashMap<>();
                for (YingjieshengJobCard c : YingjieshengJobCard.parseList(safeBody(response))) {
                    byId.put(c.getJobId(), c);
                }
                cache.set(byId);
                seq.incrementAndGet();
            }
        });
        try {
            long before = seq.get();
            navigate(page, searchUrl, listener);
            if (!HumanVerification.waitIfShowing(page, DISPLAY_NAME, waitMinutes, listener, stop)) {
                return;
            }
            waitForResponse(seq, before, stop);
            if (city != null && !city.isBlank() && !pickCity(page, city.trim(), seq, stop)) {
                listener.onProgress("【" + keyword + "】筛选栏里没有「" + city + "」，按全国搜，城市在本地过滤");
            }
            int processed = 0;
            for (int pageNum = 1; pageNum <= MAX_PAGE; pageNum++) {
                if (stop.getAsBoolean()) {
                    listener.onProgress("收到停止指令，中断采集（已处理 " + processed + " 个）");
                    break;
                }
                if (pageNum > 1) {
                    long mark = seq.get();
                    if (!clickNextPage(page)) {
                        listener.onProgress("【" + keyword + "】已到最后一页");
                        break;
                    }
                    if (!HumanVerification.waitIfShowing(page, DISPLAY_NAME, waitMinutes, listener, stop)) {
                        return;
                    }
                    if (!waitForResponse(seq, mark, stop)) {
                        listener.onProgress("【" + keyword + "】翻页后没有新结果，停止");
                        break;
                    }
                    sleep(800);
                }
                int count = itemCount(page);
                if (count == 0) {
                    listener.onProgress("【" + keyword + "】" + (pageNum == 1 ? "没有搜到岗位" : "没有更多岗位"));
                    break;
                }
                listener.onProgress("【" + keyword + "】第 " + pageNum + " 页 " + count + " 个岗位");
                Map<String, YingjieshengJobCard> entities = cache.get();
                for (int i = 0; i < count; i++) {
                    if (stop.getAsBoolean() || processed >= maxCards) {
                        break;
                    }
                    try {
                        Locator item = page.locator(ITEM).nth(i);
                        String jobId = YingjieshengJobCard.extractJobId(attr(item.locator(ITEM_LINK).first(), "href"));
                        if (jobId == null) {
                            continue;
                        }
                        YingjieshengJobCard card = entities.get(jobId);
                        if (card == null) {
                            card = cardFromDom(item, jobId);
                        }
                        processed++;
                        consumer.accept(card, page);
                    } catch (Exception e) {
                        log.debug("【{}】处理第 {} 条异常: {}", keyword, i + 1, e.getMessage());
                    }
                }
                if (processed >= maxCards) {
                    listener.onProgress("【" + keyword + "】已达单关键词上限 " + maxCards + " 个");
                    break;
                }
                if (!isLoggedIn(page)) {
                    listener.onProgress("【" + keyword + "】未登录只能看第一页，登录后可以翻页");
                    break;
                }
            }
            listener.onProgress("【" + keyword + "】处理完成，共 " + processed + " 个");
        } finally {
            closeQuietly(page);
        }
    }

    /** 接口没拦到时（比如页面先渲染了缓存）从 DOM 拼一张最小卡片，至少岗位名和公司名得有 */
    private YingjieshengJobCard cardFromDom(Locator item, String jobId) {
        YingjieshengJobCard card = new YingjieshengJobCard();
        card.setJobId(jobId);
        card.setJobUrl("https://q.yingjiesheng.com/jobdetail/" + jobId + ".html");
        card.setJobName(clean(textOf(item.locator(".left-title-name").first())));
        return card;
    }

    /** 点「工作地点」那一排里的城市。只认可见的、文字正好等于城市名的叶子节点 */
    private boolean pickCity(Page page, String city, AtomicLong seq, BooleanSupplier stop) {
        long mark = seq.get();
        try {
            Object clicked = page.evaluate("city => {"
                    + "  const nodes = [...document.querySelectorAll('span, a, li, div')];"
                    + "  const hit = nodes.find(n => n.children.length === 0 && n.textContent.trim() === city"
                    + "      && n.offsetParent !== null && !n.closest('.search-list-item-wrapper'));"
                    + "  if (!hit) return false;"
                    + "  hit.click();"
                    + "  return true;"
                    + "}", city);
            if (!Boolean.TRUE.equals(clicked)) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        return waitForResponse(seq, mark, stop);
    }

    private boolean clickNextPage(Page page) {
        try {
            Locator next = page.locator(NEXT_PAGE).first();
            if (next.count() == 0 || !next.isVisible()) {
                return false;
            }
            next.click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int itemCount(Page page) {
        for (int i = 0; i < 20; i++) {
            try {
                int n = page.locator(ITEM).count();
                if (n > 0) {
                    return n;
                }
            } catch (Exception ignore) {
            }
            sleep(500);
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // 投递
    // ------------------------------------------------------------------

    /**
     * 点列表项里的「立即申请」。成功判据：出现申请/投递成功字样，或者那一项的
     * 「立即申请」不见了 / 变成「已申请」。都没有就记失败。
     */
    public DeliveryOutcome deliver(YingjieshengJobCard card, Page listPage,
                                   YingjieshengProperties.YingjieshengConfig config,
                                   ProgressListener listener, BooleanSupplier stop) {
        String target = (card.getBrandName() != null ? card.getBrandName() : "?")
                + " | " + (card.getJobName() != null ? card.getJobName() : "?");
        if (card.isApplied()) {
            return new DeliveryOutcome(DeliveryStatus.DELIVERED, null, null);
        }
        if (config.isDryRun()) {
            listener.onProgress("预演：跳过真实投递 | " + target);
            return DeliveryOutcome.preview(null);
        }
        if (stop.getAsBoolean()) {
            return DeliveryOutcome.failed("已停止");
        }
        try {
            if (!HumanVerification.waitIfShowing(listPage, DISPLAY_NAME, config.getLoginTimeoutMinutes(),
                    listener, stop)) {
                return DeliveryOutcome.failed("人机验证未完成");
            }
            Locator item = itemOf(listPage, card.getJobId());
            if (item == null) {
                return DeliveryOutcome.failed("列表里找不到这个岗位了（页面可能刷新过）");
            }
            Locator apply = item.locator(APPLY).first();
            if (apply.count() == 0) {
                String text = clean(textOf(item));
                if (text != null && (text.contains("已申请") || text.contains("已投递"))) {
                    return new DeliveryOutcome(DeliveryStatus.DELIVERED, null, null);
                }
                return DeliveryOutcome.failed("这个岗位没有「立即申请」按钮");
            }
            if (!isLoggedIn(listPage)) {
                return DeliveryOutcome.failed("应届生求职网未登录，投不了");
            }
            apply.click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
            for (int i = 0; i < 25 && !stop.getAsBoolean(); i++) {
                sleep(400);
                String limit = bodyHit(listPage, LIMIT_TEXTS);
                if (limit != null) {
                    return DeliveryOutcome.limit("平台提示：" + limit);
                }
                if (bodyHit(listPage, new String[]{"申请成功", "投递成功"}) != null || appliedNow(listPage, card.getJobId())) {
                    closeDialogs(listPage);
                    listener.onProgress("已申请 | " + target);
                    return DeliveryOutcome.delivered(null);
                }
                confirmDialogIfAny(listPage);
            }
            String notice = visibleNotice(listPage);
            closeDialogs(listPage);
            return DeliveryOutcome.failed(notice != null ? "平台提示：" + notice : "未确认申请结果");
        } catch (Exception e) {
            log.warn("应届生投递异常 | {}: {}", target, e.getMessage());
            return DeliveryOutcome.failed(e.getMessage());
        }
    }

    private Locator itemOf(Page page, String jobId) {
        Locator item = page.locator(ITEM + ":has(a[href*='/jobdetail/" + jobId + ".html'])").first();
        return item.count() == 0 ? null : item;
    }

    private boolean appliedNow(Page page, String jobId) {
        Locator item = itemOf(page, jobId);
        if (item == null) {
            return false;
        }
        if (item.locator(APPLY).count() > 0) {
            return false;
        }
        String text = clean(textOf(item));
        return text != null && (text.contains("已申请") || text.contains("已投递"));
    }

    /** 简历选择 / 确认申请框：点可见弹窗里的主按钮 */
    private void confirmDialogIfAny(Page page) {
        try {
            Locator dialogs = page.locator(DIALOG);
            for (int i = 0; i < dialogs.count(); i++) {
                Locator d = dialogs.nth(i);
                if (!d.isVisible()) {
                    continue;
                }
                String text = clean(textOf(d));
                if (text == null || !(text.contains("简历") || text.contains("申请") || text.contains("投递"))) {
                    continue;
                }
                Locator ok = d.locator("button:has-text('确定'), button:has-text('确认'), "
                        + "button:has-text('申请'), button:has-text('投递')").last();
                if (ok.count() > 0 && ok.isVisible()) {
                    ok.click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
                    return;
                }
            }
        } catch (Exception e) {
            log.debug("应届生申请弹窗处理失败: {}", e.getMessage());
        }
    }

    private void closeDialogs(Page page) {
        try {
            Locator close = page.locator(".el-dialog__headerbtn, .el-message-box__headerbtn, [aria-label='Close']");
            for (int i = 0; i < close.count(); i++) {
                if (close.nth(i).isVisible()) {
                    close.nth(i).click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
                }
            }
        } catch (Exception ignore) {
        }
    }

    private String visibleNotice(Page page) {
        try {
            Locator msg = page.locator(".el-message__content, .el-message-box__message");
            for (int i = 0; i < msg.count(); i++) {
                if (msg.nth(i).isVisible()) {
                    return clean(textOf(msg.nth(i)));
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    private static boolean isSearchResponse(Response response) {
        try {
            String url = response.url();
            return url != null && url.contains(SEARCH_API) && response.status() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static String safeBody(Response response) {
        try {
            return response.text();
        } catch (Exception e) {
            return null;
        }
    }

    /** 等 seq 从 mark 往上走一格，即页面又发了一次搜索 */
    private boolean waitForResponse(AtomicLong seq, long mark, BooleanSupplier stop) {
        long deadline = System.currentTimeMillis() + RESPONSE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && !stop.getAsBoolean()) {
            if (seq.get() > mark) {
                return true;
            }
            sleep(250);
        }
        return seq.get() > mark;
    }

    private static String bodyHit(Page page, String[] needles) {
        try {
            Object hit = page.evaluate("ks => { const b = document.body ? document.body.innerText : '';"
                    + " return ks.find(k => b.includes(k)) || null; }", List.of(needles));
            return hit == null ? null : String.valueOf(hit);
        } catch (Exception e) {
            return null;
        }
    }

    private void navigate(Page page, String url, ProgressListener listener) {
        try {
            page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(NAV_TIMEOUT_MS)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        } catch (Exception e) {
            log.debug("导航到 {} 超时（{}），继续等页面自己加载", url, e.getMessage());
            if (listener != null) {
                listener.onProgress("页面打开较慢，继续等待…");
            }
        }
    }

    private static void configureTimeouts(Page page) {
        try {
            page.setDefaultNavigationTimeout(NAV_TIMEOUT_MS);
            page.setDefaultTimeout(ACTION_TIMEOUT_MS);
        } catch (Exception ignore) {
        }
    }

    private static String textOf(Locator locator) {
        try {
            return locator.count() == 0 ? null
                    : locator.innerText(new Locator.InnerTextOptions().setTimeout(ACTION_TIMEOUT_MS));
        } catch (Exception e) {
            return null;
        }
    }

    private static String attr(Locator locator, String name) {
        try {
            return locator.count() == 0 ? null : locator.getAttribute(name);
        } catch (Exception e) {
            return null;
        }
    }

    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? null : t;
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
