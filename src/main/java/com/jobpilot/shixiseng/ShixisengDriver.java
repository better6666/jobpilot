package com.jobpilot.shixiseng;

import com.jobpilot.browser.BrowserManager;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 实习僧浏览器驱动：登录 → 逐关键词采集 → 点"投个简历"。
 *
 * <p>实习僧是 Vue SPA，但<b>搜索列表是服务端渲染的</b>——HTML 里直接带
 * 20 条 {@code div.intern-item}，不用等 XHR，这是它比智联好采集的原因。
 *
 * <p>三个和别的平台不一样的地方（都来自 2026-09-22 实测）：
 * <ul>
 *   <li><b>岗位名混着 icon-font 私用区字符</b>（{@code \uf57f} 之类），
 *       每个字段读出来都要过 {@link ShixisengJobCard#cleanText}</li>
 *   <li><b>jobId 在 data 属性里</b>（{@code data-intern-id}），
 *       不像 51job 要挖 sensorsdata 埋点</li>
 *   <li><b>投递入口在详情页</b>（{@code .resume_apply}，文案"投个简历"），
 *       不在列表页，所以每个岗位都要开一次详情</li>
 * </ul>
 */
@Slf4j
@Component
public class ShixisengDriver {

    private final BrowserManager browserManager;

    public ShixisengDriver(BrowserManager browserManager) {
        this.browserManager = browserManager;
    }

    private static final String HOME_URL = "https://www.shixiseng.com";
    /** 列表项。data-intern-id 就是 jobId */
    private static final String ITEM_SELECTOR = "div.intern-item";
    /** 有这两个 class 之一说明列表容器出来了 */
    private static final String LIST_CONTAINER = ".result-list, .intern-wrap";
    /** 下一页。实习僧是 a 标签不是 button */
    private static final String NEXT_BUTTON = "a:has-text('下一页'), .pagination-wrap a:has-text('下一页')";
    /** 详情页的投递入口。页面顶部和底部各有一个，取第一个 */
    private static final String APPLY_BUTTON = ".resume_apply";
    /** 未登录时点"投个简历"弹出的登录框（z-index 2000，"微信扫一扫，立即登录"）。
     *  <b>这是唯一可靠的登录信号</b>——静态 DOM 全都不可靠：实测首页的底部登录条
     *  登不登录都不显示，详情页的登不登录都显示，nav 的"登录/注册"又只在详情页有。
     *  所以改成行为判定：点了之后看这个框在不在。 */
    private static final String LOGIN_DIALOG = ".login-dialog-wrap--is-show";
    /** 简历选择框的确认按钮。登录后第一次投递会弹 */
    private static final String CONFIRM_BUTTON =
            "button:has-text('确认'), button:has-text('确定'), .el-dialog__footer button--primary, " +
                    ".dialog__footer button--primary";
    /** 遮罩/弹窗的关闭位 */
    private static final String CLOSE_BUTTON =
            "[aria-label='Close'], .el-dialog__headerbtn, .icon-close, i[class*='close']";

    /** 详情页的字段容器。列表页只有岗位名/日薪/城市/公司/行业，剩下全在这儿 */
    private static final String DETAIL_MSG = ".job_msg";
    private static final String DETAIL_MONEY = ".job_msg .job_money";
    private static final String DETAIL_DEGREE = ".job_msg .job_academic";
    private static final String DETAIL_WEEK = ".job_msg .job_week";
    private static final String DETAIL_TIME = ".job_msg .job_time";
    private static final String DETAIL_JD = ".job_detail";

    private static final int NAV_TIMEOUT_MS = 45_000;
    private static final int ACTION_TIMEOUT_MS = 10_000;
    private static final int MAX_PAGE = 20;

    /** 当前正在处理的条目下标，deliver 时靠它找回列表里那一项 */
    private volatile int currentItemIndex = -1;

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------

    /**
     * 打开首页，把浏览器窗口拉起来。
     *
     * <p><b>这里不判登录态</b>——实习僧没有任何可靠的静态登录信号（首页和详情页的
     * 底部登录条登不登录都在，nav 的"登录/注册"又只在详情页出现且不是 {@code <a>}），
     * 早期版本在这儿判过，结果是未登录也报"已检测到登录态"。
     * 真正的判定挪到 {@link #deliver}：点了"投个简历"看登不登录框弹出来，
     * 那是实测唯一没有歧义的信号。
     *
     * <p>所以这个方法的职责只是：开窗口、导航、让后续流程有页面可用。
     */
    public LoginResult ensureLogin(int timeoutMinutes, ProgressListener listener, BooleanSupplier stop) {
        Page page = browserManager.context().newPage();
        configureTimeouts(page);
        try {
            navigate(page, HOME_URL, listener);
            listener.onProgress("已打开实习僧，登录态将在第一次投递时确认（未登录会弹窗提示）");
            return LoginResult.LOGGED_IN;
        } finally {
            closeQuietly(page);
        }
    }

    // ------------------------------------------------------------------
    // 采集
    // ------------------------------------------------------------------

    /**
     * 逐页采集一个关键词下的岗位。
     * <p>
     * 列表是服务端渲染的，{@code navigate} 之后 DOM 里就有条目，不用等 XHR。
     * 翻页靠 {@code ?page=N}（见 {@link ShixisengSearchUrl}），点"下一页"链接
     * 也可以但会丢掉已翻过的状态，所以直接重新导航更稳。
     */
    public void processKeyword(String searchUrl, String keyword, int maxCards,
                               ProgressListener listener, BooleanSupplier stop,
                               CardConsumer<ShixisengJobCard> consumer) {
        Page page = browserManager.context().newPage();
        // 点投递可能新开标签页，先装好关门器再开始点。
        // 挂 page 而不是 context：onPopup 只收这个页面自己弹出来的窗口，
        // 不会碰到下面 newPage 建出来的列表页（context.onPage 会，见 ZhilianDriver 的坑）
        page.onPopup(ShixisengDriver::closeQuietly);
        configureTimeouts(page);
        try {
            int processed = 0;
            for (int pageNum = 1; pageNum <= MAX_PAGE; pageNum++) {
                if (stop.getAsBoolean()) {
                    listener.onProgress("收到停止指令，中断采集（已处理 " + processed + " 个）");
                    break;
                }
                String url = pageNum == 1 ? searchUrl
                        : ShixisengSearchUrl.build(cityOf(searchUrl), keyword, pageNum);
                navigate(page, url, listener);
                if (!waitForItems(page, listener)) {
                    if (pageNum == 1) {
                        listener.onProgress("【" + keyword + "】岗位列表未加载出来（可能被登录或验证挡住），跳过");
                    } else {
                        listener.onProgress("【" + keyword + "】第 " + pageNum + " 页没有更多岗位");
                    }
                    break;
                }
                int count = page.locator(ITEM_SELECTOR).count();
                listener.onProgress("【" + keyword + "】第 " + pageNum + " 页 " + count + " 个岗位");
                if (count == 0) {
                    break;
                }
                for (int i = 0; i < count; i++) {
                    if (stop.getAsBoolean() || processed >= maxCards) {
                        break;
                    }
                    try {
                        // 翻页是整页替换，下标要重新取
                        Locator item = page.locator(ITEM_SELECTOR).nth(i);
                        if (item.count() == 0) {
                            break;
                        }
                        ShixisengJobCard card = readItem(item);
                        if (card == null || card.getJobId() == null) {
                            log.debug("【{}】第 {} 条取不到 jobId，跳过", keyword, i + 1);
                            continue;
                        }
                        // 列表页没有学历/实习月数/JD，开一次详情补上——
                        // 不补的话 degreeRules 和 jdRules 永远不命中，打分只剩职位名一组
                        enrichFromDetail(card, stop);
                        currentItemIndex = i;
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
                // 少于 20 条说明已经是最后一页（每页固定 20 条左右）
                if (count < 20) {
                    listener.onProgress("【" + keyword + "】已到最后一页");
                    break;
                }
            }
            listener.onProgress("【" + keyword + "】处理完成，共 " + processed + " 个");
        } finally {
            currentItemIndex = -1;
            closeQuietly(page);
        }
    }

    /** 从原始搜索地址里取已编码的城市段，翻页时要原样带回 */
    private static String cityOf(String searchUrl) {
        int i = searchUrl.indexOf("&city=");
        if (i < 0) {
            return null;
        }
        String rest = searchUrl.substring(i + "&city=".length());
        int amp = rest.indexOf('&');
        return amp < 0 ? rest : rest.substring(0, amp);
    }

    /**
     * 开一次详情页补字段：学历、实习月数、JD，顺便用详情页的薪资覆盖列表页的。
     *
     * <p><b>必须新开页面读，不能用列表页导航过去</b>——列表页一走，
     * {@link #processKeyword} 里 {@code nth(i)} 的下标就全废了。
     *
     * <p>详情页读不到不致命：字段留 null，打分时那几组规则自然不命中。
     */
    void enrichFromDetail(ShixisengJobCard card, BooleanSupplier stop) {
        if (card.getJobUrl() == null || stop.getAsBoolean()) {
            return;
        }
        Page detail = browserManager.context().newPage();
        detail.onPopup(ShixisengDriver::closeQuietly);
        configureTimeouts(detail);
        try {
            navigate(detail, card.getJobUrl(), null);
            for (int i = 0; i < 12; i++) {
                if (detail.locator(DETAIL_MSG).count() > 0) {
                    break;
                }
                sleep(300);
            }
            readDetailFields(detail, card);
        } catch (Exception e) {
            log.debug("详情页补字段失败 | {}: {}", card.getJobId(), e.getMessage());
        } finally {
            closeQuietly(detail);
        }
    }

    /**
     * 从已经打开的详情页读字段填进卡片。
     * <p>
     * 拆出来是为了能单测：{@link #enrichFromDetail} 要 newPage，测试里没有
     * BrowserManager；而真正值得验的是"选择器有没有写对"，那部分只依赖 Page。
     */
    void readDetailFields(Page detail, ShixisengJobCard card) {
        card.setJobDegree(ShixisengJobCard.cleanText(textOf(detail.locator(DETAIL_DEGREE).first())));
        card.setWeekDays(ShixisengJobCard.cleanText(textOf(detail.locator(DETAIL_WEEK).first())));
        card.setMonthDuration(ShixisengJobCard.cleanText(textOf(detail.locator(DETAIL_TIME).first())));
        card.setPostDescription(ShixisengJobCard.cleanText(textOf(detail.locator(DETAIL_JD).first())));
        // 详情页的薪资比列表页准（列表那条常被 icon-font 啃成"-/天"）
        String money = ShixisengJobCard.cleanText(textOf(detail.locator(DETAIL_MONEY).first()));
        if (money != null) {
            card.setSalaryDesc(money);
        }
    }

    /**
     * 从一条 {@code div.intern-item} 读字段。
     *
     * <p>结构（实测）：
     * <pre>
     *   div.intern-item[data-intern-id=inn_xxx]
     *     div.intern-detail__job
     *       p &gt; a.title           岗位名（混 icon-font）
     *       p &gt; span.day          日薪
     *       p.tip &gt; span.city      城市
     *       p.tip &gt; span.font      每周几天 / 几个月
     *     div.intern-detail__company
     *       p &gt; a.title           公司名
     *       p.tip &gt; span.ellipsis  行业
     * </pre>
     */
    ShixisengJobCard readItem(Locator item) {
        ShixisengJobCard card = new ShixisengJobCard();
        String id = attr(item, "data-intern-id");
        card.setJobId(id);

        Locator title = item.locator(".intern-detail__job a.title").first();
        card.setJobName(ShixisengJobCard.cleanText(textOf(title)));

        Locator day = item.locator(".intern-detail__job .day").first();
        card.setSalaryDesc(ShixisengJobCard.cleanText(textOf(day)));

        Locator city = item.locator(".intern-detail__job .city").first();
        card.setCityName(ShixisengJobCard.cleanText(textOf(city)));

        // tip 里有两组 span.font：第一个是"X天/周"，第二个是"X个月"
        List<String> tips = new ArrayList<>();
        Locator fonts = item.locator(".intern-detail__job .tip span.font");
        for (int i = 0; i < fonts.count() && i < 4; i++) {
            String t = ShixisengJobCard.cleanText(textOf(fonts.nth(i)));
            if (t != null) {
                tips.add(t);
            }
        }
        for (String t : tips) {
            if (t.contains("周")) {
                card.setWeekDays(t);
            } else if (t.contains("个月") || t.contains("以上") || t.contains("少于")) {
                card.setMonthDuration(t);
            }
        }

        Locator company = item.locator(".intern-detail__company a.title").first();
        card.setBrandName(ShixisengJobCard.cleanText(textOf(company)));

        Locator industry = item.locator(".intern-detail__company .tip span.ellipsis").first();
        card.setIndustryName(ShixisengJobCard.cleanText(textOf(industry)));

        Locator link = item.locator(".intern-detail__job a.title").first();
        String href = attr(link, "href");
        if (href != null && !href.isBlank()) {
            card.setJobUrl(href.startsWith("http") ? href : "https://www.shixiseng.com" + href);
        }
        return card;
    }

    // ------------------------------------------------------------------
    // 投递
    // ------------------------------------------------------------------

    /**
     * 投递一个岗位：开详情页 → 点"投个简历" → 处理简历选择框 → 关遮罩。
     *
     * <p><b>投递后的确认弹窗形态未经真实验证</b>——实习僧详情页公开可见，
     * 但点"投个简历"之后走的是登录态流程，本机没有实习僧账号。
     * 已按页面可见部分实现，登录后需实测校准。
     */
    public DeliveryOutcome deliver(ShixisengJobCard card, Page listPage,
                                   ShixisengProperties.ShixisengConfig config,
                                   ProgressListener listener, BooleanSupplier stop) {
        String target = (card.getBrandName() != null ? card.getBrandName() : "?")
                + " | " + (card.getJobName() != null ? card.getJobName() : "?");
        if (config.isDryRun()) {
            listener.onProgress("预演：跳过真实投递 | " + target);
            return DeliveryOutcome.preview(config.getSayHi());
        }
        if (stop.getAsBoolean()) {
            return DeliveryOutcome.failed("已停止");
        }
        if (card.getJobUrl() == null) {
            return DeliveryOutcome.failed("拿不到详情地址，无法投递");
        }
        Page detail = browserManager.context().newPage();
        detail.onPopup(ShixisengDriver::closeQuietly);
        configureTimeouts(detail);
        try {
            navigate(detail, card.getJobUrl(), listener);
            if (!waitForApplyButton(detail)) {
                return DeliveryOutcome.failed("详情页没加载出投递入口（可能被登录或验证挡住）");
            }
            Locator apply = detail.locator(APPLY_BUTTON).first();
            String btnText = ShixisengJobCard.cleanText(textOf(apply));
            if (btnText != null && (btnText.contains("已投递") || btnText.contains("已投"))) {
                return new DeliveryOutcome(DeliveryStatus.DELIVERED, null, null);
            }
            closeAnyModalOverlays(detail);
            apply.click(new Locator.ClickOptions()
                    .setTimeout(ACTION_TIMEOUT_MS).setForce(true));

            // 点了才看得出登没登：未登录时弹的是登录框而不是投递流程。
            // 静态 DOM 判不了（首页/详情页的底部登录条登不登录都在，nav 的
            // "登录/注册"又只在详情页有），所以用行为判——实测唯一可靠的。
            // 这时候只能等用户在那个已经打开的真浏览器窗口里自己扫码/登录，
            // 登录框消失即视为成功，然后再点一次。
            if (isLoginDialogVisible(detail)
                    && !waitForLogin(detail, listener, stop, config.getLoginTimeoutMinutes())) {
                return DeliveryOutcome.failed("等待登录超时，未投递");
            }
            if (isLoginDialogVisible(detail)) {
                closeAnyModalOverlays(detail);
                apply = detail.locator(APPLY_BUTTON).first();
                if (apply.count() == 0) {
                    return DeliveryOutcome.failed("登录后投递入口不见了（页面可能刷新过）");
                }
                apply.click(new Locator.ClickOptions()
                        .setTimeout(ACTION_TIMEOUT_MS).setForce(true));
            }

            // 简历选择框：没有预设默认简历时必弹，勾上默认再确认
            if (!handleResumeDialog(detail, stop)) {
                closeAnyModalOverlays(detail);
                return DeliveryOutcome.failed("简历选择框没能处理，放弃投递");
            }
            // 投递成功会给提示，等一等再收尾
            for (int i = 0; i < 10 && !stop.getAsBoolean(); i++) {
                if (isApplySucceeded(detail)) {
                    closeAnyModalOverlays(detail);
                    listener.onProgress("已投递 | " + target);
                    return DeliveryOutcome.delivered(config.getSayHi());
                }
                sleep(400);
            }
            closeAnyModalOverlays(detail);
            return DeliveryOutcome.failed("未确认投递结果");
        } catch (Exception e) {
            log.warn("实习僧投递异常 | {}: {}", target, e.getMessage());
            return DeliveryOutcome.failed(e.getMessage());
        } finally {
            closeQuietly(detail);
        }
    }

    /** 详情页的投递入口是否已出现 */
    private boolean waitForApplyButton(Page page) {
        for (int i = 0; i < 15; i++) {
            try {
                if (page.locator(APPLY_BUTTON).count() > 0) {
                    return true;
                }
            } catch (Exception ignore) {
            }
            sleep(500);
        }
        return false;
    }

    /**
     * 点投递后是否弹出了登录框。这是实习僧唯一可靠的登录判定。
     *
     * <p>为什么不用静态 DOM：实测（2026-09-22，同一台机器登录/未登录各跑一遍）——
     * <ul>
     *   <li>首页底部登录条 {@code .footer-login--is-show}：未登录时 count=0，
     *       登录后也是 count=0，<b>两边都判不出</b></li>
     *   <li>详情页底部登录条：未登录 count=1 可见，<b>登录后还是 count=1 可见</b></li>
     *   <li>nav 的"登录/注册"：只在详情页且未登录时出现，但元素不是 {@code <a>}，
     *       {@code a:has-text()} 选不中</li>
     * </ul>
     * 而点了"投个简历"之后：未登录必弹 {@link #LOGIN_DIALOG}，登录后不弹。
     * 行为判定没有歧义，所以放在 {@link #deliver} 里点过之后判。
     */
    private boolean isLoginDialogVisible(Page page) {
        try {
            Locator dialog = page.locator(LOGIN_DIALOG).first();
            return dialog.count() > 0 && dialog.isVisible();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 等用户在弹出的浏览器窗口里登录。等到登录框消失为止。
     *
     * <p>等待时长取配置里的 loginTimeoutMinutes——和扫码登录同一个字段，
     * 用户扫微信也需要时间。期间用户点"停止"能立刻中断。
     */
    private boolean waitForLogin(Page page, ProgressListener listener, BooleanSupplier stop,
                                 int timeoutMinutes) {
        listener.onProgress("检测到未登录，请在浏览器窗口里登录实习僧（微信扫码/短信/QQ均可，"
                + timeoutMinutes + " 分钟内有效）…");
        long deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L;
        while (System.currentTimeMillis() < deadline && !stop.getAsBoolean()) {
            sleep(2000);
            if (!isLoginDialogVisible(page)) {
                listener.onProgress("登录成功，继续投递");
                return true;
            }
        }
        return false;
    }

    /**
     * 处理简历选择框。返回是否顺利走到确认。
     *
     * <p>没有默认简历时实习僧会弹选择框，要先勾"默认"再点确认。
     * 弹窗形态未实测，这里按常见两套（ElementUI / 自定义 dialog）兜底。
     */
    private boolean handleResumeDialog(Page page, BooleanSupplier stop) {
        for (int i = 0; i < 8 && !stop.getAsBoolean(); i++) {
            try {
                Locator dialog = page.locator(
                        ".el-dialog, [role='dialog'], .dialog, .resume-dialog").first();
                if (dialog.count() == 0 || !dialog.isVisible()) {
                    // 没有弹窗：要么直接投成功了，要么还在等
                    if (isApplySucceeded(page)) {
                        return true;
                    }
                    sleep(400);
                    continue;
                }
                String text = dialog.innerText(new Locator.InnerTextOptions()
                        .setTimeout(ACTION_TIMEOUT_MS));
                if (text != null && (text.contains("成功") || text.contains("已投递"))) {
                    return true;
                }
                if (text != null && text.contains("简历")) {
                    // 先勾默认简历（有就勾，没有就算了）
                    Locator def = dialog.locator(
                            "label:has-text('默认'), span:has-text('默认'), input[type='radio']").first();
                    if (def.count() > 0) {
                        try {
                            def.click(new Locator.ClickOptions()
                                    .setTimeout(ACTION_TIMEOUT_MS).setForce(true));
                        } catch (Exception ignore) {
                        }
                    }
                    Locator ok = dialog.locator(CONFIRM_BUTTON).first();
                    if (ok.count() > 0) {
                        ok.click(new Locator.ClickOptions()
                                .setTimeout(ACTION_TIMEOUT_MS).setForce(true));
                        sleep(500);
                        continue;
                    }
                }
                // 无关弹窗，关掉继续
                closeAnyModalOverlays(page);
                return true;
            } catch (Exception e) {
                log.debug("实习僧简历弹窗处理异常: {}", e.getMessage());
                sleep(400);
            }
        }
        return false;
    }

    /** 投递是否成功：找"投递成功"类提示 */
    private boolean isApplySucceeded(Page page) {
        try {
            Locator hit = page.locator(
                    "text=投递成功, text=已投递, .el-message__content, .toast, .message-success").first();
            if (hit.count() > 0 && hit.isVisible()) {
                return true;
            }
        } catch (Exception ignore) {
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

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

    private void configureTimeouts(Page page) {
        try {
            page.setDefaultNavigationTimeout(NAV_TIMEOUT_MS);
            page.setDefaultTimeout(ACTION_TIMEOUT_MS);
        } catch (Exception ignore) {
        }
    }

    private boolean waitForItems(Page page, ProgressListener listener) {
        for (int i = 0; i < 20; i++) {
            try {
                if (page.locator(ITEM_SELECTOR).count() > 0) {
                    return true;
                }
                if (page.locator(LIST_CONTAINER).count() > 0) {
                    // 容器出来了但没条目：可能是空结果，再等一轮
                    sleep(500);
                }
            } catch (Exception ignore) {
            }
            sleep(500);
        }
        return false;
    }

    private static void closeAnyModalOverlays(Page page) {
        try {
            Locator close = page.locator(CLOSE_BUTTON).first();
            if (close.count() > 0 && close.isVisible()) {
                close.click(new Locator.ClickOptions()
                        .setTimeout(ACTION_TIMEOUT_MS).setForce(true));
            }
        } catch (Exception ignore) {
        }
    }

    private static String textOf(Locator locator) {
        try {
            if (locator.count() == 0) {
                return null;
            }
            return locator.innerText(new Locator.InnerTextOptions().setTimeout(ACTION_TIMEOUT_MS));
        } catch (Exception e) {
            return null;
        }
    }

    private static String attr(Locator locator, String name) {
        try {
            if (locator.count() == 0) {
                return null;
            }
            String v = locator.getAttribute(name);
            return v == null || v.isBlank() ? null : v.trim();
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
