package com.jobpilot.nowcoder;

import com.jobpilot.browser.BrowserManager;
import com.jobpilot.browser.HumanVerification;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 牛客浏览器驱动：登录 → 逐关键词采集 → 详情页点「立即申请」。
 *
 * <p>2026-10-14 实测的几个关键点：
 * <ul>
 *   <li><b>搜索结果不走可拦截的接口</b>：页面里的 XHR 全是热搜、广告、登录轮询，
 *       岗位列表是首屏渲染进 DOM 的，所以只能读 {@code .job-card-item}</li>
 *   <li><b>URL 里的 city / page 参数无效</b>，翻页只能点 {@code .pagination-wrap li}；
 *       未登录时点了也不翻（停在第 1 页），所以翻页后必须核对首条岗位变没变</li>
 *   <li><b>登录判定用接口</b>：{@code /profile/user-info-v2} 未登录时回
 *       {@code {"code":999,"msg":"用户未登录"}}。页面上那套登录表单匿名时一直渲染着，
 *       按 DOM 判会误报</li>
 *   <li><b>投递入口在详情页</b>（{@code button:has-text('立即申请')}），JD 也在详情页，
 *       所以一个岗位只开一次详情：采集时开、补字段，投递时复用同一个页面</li>
 *   <li>卡片上标着「简历直投官网」「直达官网」的岗位，申请会跳到企业官网，投不了</li>
 * </ul>
 */
@Slf4j
@Component
public class NowcoderDriver {

    private static final String HOME_URL = "https://www.nowcoder.com/";
    private static final String SEARCH_BASE = "https://www.nowcoder.com/search/job?type=job&query=";
    private static final String USER_INFO_API = "/profile/user-info-v2";
    private static final String DISPLAY_NAME = "牛客";

    private static final String ITEM_SELECTOR = ".job-card-item";
    private static final String PAGER_ITEMS = ".pagination-wrap li";
    private static final String APPLY_BUTTON = "button:has-text('立即申请'), button:has-text('投递简历'), "
            + "button:has-text('已申请'), button:has-text('已投递')";
    private static final String DETAIL_JD = ".job-detail-infos";
    private static final String DETAIL_HR = ".fs-16.tw-font-medium";
    private static final String DIALOG = ".el-dialog, .el-message-box, [role='dialog']";
    private static final String CONFIRM_IN_DIALOG = "button:has-text('确认'), button:has-text('确定'), "
            + "button:has-text('投递'), button:has-text('申请')";

    private static final String[] SUCCESS_TEXTS = {"投递成功", "申请成功", "已投递", "已申请"};
    private static final String[] LIMIT_TEXTS = {"今日投递已达上限", "投递次数已达上限", "今日申请次数已用完", "投递太频繁"};

    private static final int NAV_TIMEOUT_MS = 45_000;
    private static final int ACTION_TIMEOUT_MS = 10_000;
    private static final int MAX_PAGE = 20;

    private final BrowserManager browserManager;

    /** 采集时开的详情页，投递时复用；consumer 回来后统一关掉 */
    private volatile Page openDetail;
    private volatile String openDetailJobId;
    /** 当前跑批的登录等待分钟数，验证页也按这个等 */
    private volatile int waitMinutes = 5;

    public NowcoderDriver(BrowserManager browserManager) {
        this.browserManager = browserManager;
    }

    static String searchUrl(String keyword) {
        return SEARCH_BASE + URLEncoder.encode(keyword == null ? "" : keyword.trim(), StandardCharsets.UTF_8);
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
            if (isLoggedIn(page)) {
                listener.onProgress("已检测到牛客登录态");
                return LoginResult.LOGGED_IN;
            }
            listener.onProgress("未登录牛客，请在浏览器窗口右上角点「登录 / 注册」完成登录（等待 "
                    + timeoutMinutes + " 分钟）");
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

    /** 问牛客自己的用户信息接口；code==0 才算登录，999 是"用户未登录" */
    boolean isLoggedIn(Page page) {
        try {
            Object code = page.evaluate("async (api) => {"
                    + "  try {"
                    + "    const r = await fetch(api + '?_=' + Date.now(), {credentials: 'include'});"
                    + "    const j = await r.json();"
                    + "    return j && typeof j.code !== 'undefined' ? String(j.code) : null;"
                    + "  } catch (e) { return null; }"
                    + "}", USER_INFO_API);
            return "0".equals(String.valueOf(code));
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 采集
    // ------------------------------------------------------------------

    public void processKeyword(String searchUrl, String keyword, int maxCards,
                               ProgressListener listener, BooleanSupplier stop,
                               CardConsumer<NowcoderJobCard> consumer) {
        Page page = browserManager.context().newPage();
        page.onPopup(NowcoderDriver::closeQuietly);
        configureTimeouts(page);
        try {
            navigate(page, searchUrl, listener);
            if (!HumanVerification.waitIfShowing(page, DISPLAY_NAME, waitMinutes, listener, stop)) {
                return;
            }
            int processed = 0;
            for (int pageNum = 1; pageNum <= MAX_PAGE; pageNum++) {
                if (stop.getAsBoolean()) {
                    listener.onProgress("收到停止指令，中断采集（已处理 " + processed + " 个）");
                    break;
                }
                if (pageNum > 1 && !gotoPage(page, pageNum)) {
                    listener.onProgress("【" + keyword + "】已到最后一页");
                    break;
                }
                if (!waitForItems(page)) {
                    listener.onProgress(pageNum == 1
                            ? "【" + keyword + "】没有搜到岗位（牛客以互联网/校招岗为主，冷门方向可能没有）"
                            : "【" + keyword + "】第 " + pageNum + " 页没有更多岗位");
                    break;
                }
                int count = page.locator(ITEM_SELECTOR).count();
                listener.onProgress("【" + keyword + "】第 " + pageNum + " 页 " + count + " 个岗位");
                for (int i = 0; i < count; i++) {
                    if (stop.getAsBoolean() || processed >= maxCards) {
                        break;
                    }
                    try {
                        NowcoderJobCard card = readItem(page.locator(ITEM_SELECTOR).nth(i));
                        if (card == null || card.getJobId() == null) {
                            continue;
                        }
                        // 官网直投的不开详情：反正投不了，省一次页面加载、少一次被风控的机会
                        if (!card.isExternal()) {
                            enrichFromDetail(card, stop);
                        }
                        processed++;
                        consumer.accept(card, page);
                    } catch (Exception e) {
                        log.debug("【{}】处理第 {} 条异常: {}", keyword, i + 1, e.getMessage());
                    } finally {
                        closeOpenDetail();
                    }
                }
                if (processed >= maxCards) {
                    listener.onProgress("【" + keyword + "】已达单关键词上限 " + maxCards + " 个");
                    break;
                }
            }
            listener.onProgress("【" + keyword + "】处理完成，共 " + processed + " 个");
        } finally {
            closeOpenDetail();
            closeQuietly(page);
        }
    }

    /** 一张卡片的所有文字一次取回来：逐个 locator 读要十几次往返，慢且容易撞上重渲染 */
    @SuppressWarnings("unchecked")
    NowcoderJobCard readItem(Locator item) {
        Object raw = item.evaluate("el => {"
                + "  const t = (sel) => { const n = el.querySelector(sel); return n ? n.innerText : null; };"
                + "  const all = (sel) => [...el.querySelectorAll(sel)].map(n => n.innerText);"
                + "  const a = el.querySelector(\"a[href*='/jobs/detail/']\");"
                + "  return { href: a ? a.getAttribute('href') : null, title: t('.job-name'),"
                + "    salary: t('.job-salary'), tags: all('.job-info-item'),"
                + "    company: t('.company-name'), companyInfo: all('.company-info-item'),"
                + "    text: el.innerText };"
                + "}");
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        return cardFrom((Map<String, Object>) map);
    }

    /** 从 evaluate 回来的那一包字段拼卡片。拆出来是为了脱离浏览器单测 */
    @SuppressWarnings("unchecked")
    static NowcoderJobCard cardFrom(Map<String, Object> fields) {
        String href = (String) fields.get("href");
        String jobId = NowcoderJobCard.extractJobId(href);
        if (jobId == null) {
            return null;
        }
        NowcoderJobCard card = new NowcoderJobCard();
        card.setJobId(jobId);
        card.setJobUrl("https://www.nowcoder.com/jobs/detail/" + jobId);
        card.applyTitle((String) fields.get("title"));
        card.setSalaryDesc(NowcoderJobCard.clean((String) fields.get("salary")));
        card.applyTags((List<String>) fields.get("tags"));
        card.setBrandName(NowcoderJobCard.clean((String) fields.get("company")));
        card.applyCompanyInfo((List<String>) fields.get("companyInfo"));
        card.setExternal(NowcoderJobCard.isExternalText((String) fields.get("text")));
        return card;
    }

    /** 开详情页补 JD 和 HR 名字。页面留着给 {@link #deliver} 用 */
    void enrichFromDetail(NowcoderJobCard card, BooleanSupplier stop) {
        if (stop.getAsBoolean()) {
            return;
        }
        Page detail = browserManager.context().newPage();
        detail.onPopup(NowcoderDriver::closeQuietly);
        configureTimeouts(detail);
        openDetail = detail;
        openDetailJobId = card.getJobId();
        try {
            navigate(detail, card.getJobUrl(), null);
            for (int i = 0; i < 16 && detail.locator(DETAIL_JD).count() == 0; i++) {
                sleep(300);
            }
            card.setPostDescription(NowcoderJobCard.clean(textOf(detail.locator(DETAIL_JD).first())));
            card.setBossName(NowcoderJobCard.clean(textOf(detail.locator(DETAIL_HR).first())));
        } catch (Exception e) {
            log.debug("牛客详情页补字段失败 | {}: {}", card.getJobId(), e.getMessage());
        }
    }

    private boolean gotoPage(Page page, int pageNum) {
        String before = firstJobHref(page);
        try {
            Locator items = page.locator(PAGER_ITEMS);
            Locator target = null;
            for (int i = 0; i < items.count(); i++) {
                if (String.valueOf(pageNum).equals(safeText(items.nth(i)))) {
                    target = items.nth(i);
                    break;
                }
            }
            if (target == null) {
                return false;
            }
            target.click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
        } catch (Exception e) {
            return false;
        }
        // 未登录时点页码不翻页：首条岗位没变就当到底了，否则同一页会被处理两遍
        for (int i = 0; i < 20; i++) {
            sleep(500);
            String after = firstJobHref(page);
            if (after != null && !after.equals(before)) {
                return true;
            }
        }
        return false;
    }

    private String firstJobHref(Page page) {
        try {
            Locator a = page.locator(ITEM_SELECTOR + " a[href*='/jobs/detail/']").first();
            return a.count() == 0 ? null : a.getAttribute("href");
        } catch (Exception e) {
            return null;
        }
    }

    private boolean waitForItems(Page page) {
        for (int i = 0; i < 24; i++) {
            try {
                if (page.locator(ITEM_SELECTOR).count() > 0) {
                    return true;
                }
                Locator empty = page.locator("text=未找到相关结果");
                if (empty.count() > 0 && empty.first().isVisible()) {
                    return false;
                }
            } catch (Exception ignore) {
            }
            sleep(500);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 投递
    // ------------------------------------------------------------------

    /**
     * 在详情页点「立即申请」。成功判据（任中其一）：页面出现投递/申请成功字样，
     * 或者按钮文字变成「已申请/已投递」。没看到任何一个就记失败，不虚报。
     */
    public DeliveryOutcome deliver(NowcoderJobCard card, NowcoderProperties.NowcoderConfig config,
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
        Page detail = card.getJobId().equals(openDetailJobId) ? openDetail : null;
        boolean ownPage = detail == null;
        if (ownPage) {
            detail = browserManager.context().newPage();
            detail.onPopup(NowcoderDriver::closeQuietly);
            configureTimeouts(detail);
            navigate(detail, card.getJobUrl(), listener);
        }
        try {
            if (!HumanVerification.waitIfShowing(detail, DISPLAY_NAME, config.getLoginTimeoutMinutes(), listener, stop)) {
                return DeliveryOutcome.failed("人机验证未完成");
            }
            Locator button = waitForApplyButton(detail);
            if (button == null) {
                return DeliveryOutcome.failed("详情页没有申请按钮（岗位可能已下线）");
            }
            String text = safeText(button);
            if (text != null && (text.contains("已申请") || text.contains("已投递"))) {
                return new DeliveryOutcome(DeliveryStatus.DELIVERED, null, null);
            }
            button.click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
            for (int i = 0; i < 30 && !stop.getAsBoolean(); i++) {
                sleep(400);
                String limit = pageTextHit(detail, LIMIT_TEXTS);
                if (limit != null) {
                    return DeliveryOutcome.limit("牛客提示：" + limit);
                }
                if (applied(detail)) {
                    closeDialogs(detail);
                    listener.onProgress("已申请 | " + target);
                    return DeliveryOutcome.delivered(null);
                }
                // 只查一次：每轮都打用户信息接口太吵。2 秒还没结果多半是被登录拦了
                if (i == 5 && !isLoggedIn(detail)) {
                    return DeliveryOutcome.failed("点申请时发现牛客未登录，请重新登录后再跑");
                }
                confirmDialogIfAny(detail);
            }
            String notice = visibleNotice(detail);
            return DeliveryOutcome.failed(notice != null ? "平台提示：" + notice : "未确认申请结果");
        } catch (Exception e) {
            log.warn("牛客投递异常 | {}: {}", target, e.getMessage());
            return DeliveryOutcome.failed(e.getMessage());
        } finally {
            if (ownPage) {
                closeQuietly(detail);
            }
        }
    }

    private Locator waitForApplyButton(Page page) {
        for (int i = 0; i < 20; i++) {
            try {
                Locator b = page.locator(APPLY_BUTTON).first();
                if (b.count() > 0 && b.isVisible()) {
                    return b;
                }
            } catch (Exception ignore) {
            }
            sleep(400);
        }
        return null;
    }

    private boolean applied(Page page) {
        try {
            Locator b = page.locator(APPLY_BUTTON).first();
            String text = b.count() > 0 ? safeText(b) : null;
            if (text != null && (text.contains("已申请") || text.contains("已投递"))) {
                return true;
            }
        } catch (Exception ignore) {
        }
        return pageTextHit(page, new String[]{"投递成功", "申请成功"}) != null;
    }

    /**
     * 点申请后可能弹简历选择框或"确认投递"框：点里面的主按钮。
     * 只点<b>可见</b>的弹窗——ElementUI 页面常驻好几个 display:none 的 el-dialog
     */
    private void confirmDialogIfAny(Page page) {
        try {
            Locator dialogs = page.locator(DIALOG);
            for (int i = 0; i < dialogs.count(); i++) {
                Locator d = dialogs.nth(i);
                if (!d.isVisible()) {
                    continue;
                }
                String text = safeText(d);
                if (text == null || !(text.contains("简历") || text.contains("投递") || text.contains("申请"))) {
                    continue;
                }
                Locator ok = d.locator(CONFIRM_IN_DIALOG).last();
                if (ok.count() > 0 && ok.isVisible()) {
                    ok.click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
                    return;
                }
            }
        } catch (Exception e) {
            log.debug("牛客申请弹窗处理失败: {}", e.getMessage());
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

    /** ElementUI 的 toast / message-box 正文，投递被拒时的原话 */
    private String visibleNotice(Page page) {
        try {
            Locator msg = page.locator(".el-message__content, .el-message-box__message");
            for (int i = 0; i < msg.count(); i++) {
                if (msg.nth(i).isVisible()) {
                    return NowcoderJobCard.clean(safeText(msg.nth(i)));
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static String pageTextHit(Page page, String[] needles) {
        try {
            Object hit = page.evaluate("ks => { const b = document.body ? document.body.innerText : '';"
                    + " return ks.find(k => b.includes(k)) || null; }", List.of(needles));
            return hit == null ? null : String.valueOf(hit);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    private void closeOpenDetail() {
        Page p = openDetail;
        openDetail = null;
        openDetailJobId = null;
        if (p != null) {
            closeQuietly(p);
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

    private static String safeText(Locator locator) {
        String t = textOf(locator);
        return t == null ? null : t.trim();
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
