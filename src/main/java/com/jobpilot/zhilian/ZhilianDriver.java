package com.jobpilot.zhilian;

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

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 智联招聘浏览器操作层。
 * <p>
 * <b>线程约束：所有方法都必须在 BrowserManager 的 dispatcher 线程里调用</b>
 * （即 submit/submitAsync 的任务体内）。Playwright 客户端非线程安全。
 * <p>
 * 已知智联的坑（都来自实测，改这段代码前先读）：
 * <ul>
 *   <li><b>关键词和薪资必须走 query</b>：旧版先导航 {@code /sou/jl639/p1} 再往
 *       输入框敲关键词，SPA 会跳到不含 {@code sl} 的地址，薪资过滤被丢。
 *       现在直接拼 {@code /jobs?pageMode=search&jl=&kw=&sl=}，服务端渲染，
 *       两个条件都生效</li>
 *   <li><b>jobId 只在详情面板里</b>：列表卡片上唯一的 {@code a[href]} 是公司页
 *       （{@code /companydetail/CZL....htm}），没有数字 jobId。jobId 只存在于
 *       点开卡片后右侧 {@code a[href*='jobdetail/']} 链接里，所以每张卡都必须
 *       点一次面板才能拿到，采卡片就是"点卡+读面板"</li>
 *   <li><b>薪资 sl 是区间重叠</b>不是包含：{@code sl=12000,20000} 会放行
 *       8000-15000 这种，预演时不要以为是过滤坏了</li>
 *   <li><b>没有任何可拦的接口</b>：列表是整页 DOM，字段只能逐条读，
 *       学历/HR/行业/规模/JD 一律采不到，只能留空</li>
 *   <li><b>投递要点两下</b>：点卡片出详情面板 → 点"立即投递" → 可能再出
 *       简历选择框 → 点"投递简历"确认。账号里没预设默认简历时第二步必弹</li>
 *   <li><b>点投递可能新开标签页</b>，必须注册 context.onPage 及时关掉，
 *       否则浏览器焦点被抢走，后续点击全废</li>
 *   <li>遮罩层（modal/mask/backdrop/打招呼弹窗）会吃掉所有点击，
 *       <b>每张卡片点之前和每次投递之后都要 JS 暴力 remove</b></li>
 *   <li>上限判据是 {@code div.a-job-apply-workflow} 的文本含"达到上限"，
 *       命中就停整个任务</li>
 *   <li>登录态只能靠 DOM 判（{@code a.home-header__c-no-login} 消失）：
 *       智联只支持微信扫码，cookie 里没有稳定的登录 token，
 *       实测老 profile 的 zhaopin.com cookie 全是设备/统计字段</li>
 *   <li>末页判据是"下一页"按钮 class 含 {@code soupager__btn--disable}</li>
 * </ul>
 */
@Slf4j
@Component
public class ZhilianDriver {

    private final BrowserManager browserManager;

    public ZhilianDriver(BrowserManager browserManager) {
        this.browserManager = browserManager;
    }

    private static final String DOMAIN = "https://www.zhaopin.com";
    private static final String HOME_URL = "https://www.zhaopin.com/sou/";
    /** 存在这个节点就是未登录。登录后智联会把它从 DOM 里摘掉 */
    private static final String NO_LOGIN_ANCHOR = "a.home-header__c-no-login";
    private static final String CARD_SELECTOR = "div.job-card";
    private static final String LIST_CONTAINER = "div.positionlist";
    /** 详情面板。点卡片后异步拉出来，jobId 就在它的 jobdetail 链接里 */
    private static final String PANEL_SELECTOR = "div.job-detail-panel";
    private static final String NEXT_BUTTON = "a.soupager__btn:has-text(\"下一页\")";
    private static final String NEXT_DISABLED_CLASS = "soupager__btn--disable";

    /** 认学历用。经验按"含年"认，这里是学历的判据 */
    private static final List<String> DEGREE_WORDS = List.of(
            "学历不限", "初中", "高中", "中专", "中技", "大专", "专科", "本科", "硕士", "博士", "MBA");

    /** 立即投递三选一：详情面板的正式按钮 + 两个文案兜底 */
    private static final String APPLY_BUTTON =
            "button.job-detail-summary__apply, button:has-text('立即投递'), button:has-text('投个简历')";
    /** 确认按钮。文案可能被包好几层，所以还要一条 JS 兜底 */
    private static final String CONFIRM_BUTTON =
            "button:has-text('投递简历'), button:has-text('确定投递'), button:has-text('确认投递')";
    private static final String DEFAULT_RESUME =
            "label:has-text('每次投递默认发送该简历'), span:has-text('每次投递默认发送该简历')";
    /** 投递后置弹窗（向HR打招呼/投递成功）和遮罩，点卡片前也要清 */
    private static final String GREETING_CLOSE =
            ".deliver-greeting-modal [class*='close'], .deliver-greeting-modal i, " +
                    "button:has-text('我知道了'), button:has-text('跳过'), [aria-label='Close'], i.icon-close";

    private static final int NAV_TIMEOUT_MS = 45_000;
    private static final int ACTION_TIMEOUT_MS = 10_000;
    private static final int MAX_PAGE = 50;

    /**
     * 当前正在处理的卡片下标。
     * <p>
     * 采集阶段和投递阶段是分开的两次调用（编排层要在中间做去重和过滤），
     * 而智联的详情面板是靠"点哪张卡"决定的，只能靠下标找回那张卡。
     * 整个流程跑在单线程 dispatcher 上，collect 里设好值、deliver 里立刻用，
     * 不存在竞争。
     */
    private volatile int currentCardIndex = -1;

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------

    /**
     * 打开搜索页并等待登录。
     * <p>
     * 智联只支持微信扫码，而且登录态在 cookie 里没有稳定 token，
     * 所以这里用 DOM 判：{@code a.home-header__c-no-login} 还在就是没登录。
     */
    public LoginResult ensureLogin(int timeoutMinutes, ProgressListener listener, BooleanSupplier stop) {
        Page page = browserManager.context().newPage();
        configureTimeouts(page);
        try {
            navigate(page, HOME_URL, listener);
            if (!isLoggedOut(page)) {
                listener.onProgress("已检测到智联招聘登录态，直接进入岗位列表");
                return LoginResult.LOGGED_IN;
            }
            listener.onProgress("未检测到智联招聘登录态，请在浏览器窗口里微信扫码登录（等待 "
                    + timeoutMinutes + " 分钟）");
            guideToLoginPage(page);
            long deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L;
            while (System.currentTimeMillis() < deadline && !stop.getAsBoolean()) {
                sleep(2000);
                if (!isLoggedOut(page)) {
                    listener.onProgress("扫码登录成功");
                    return LoginResult.LOGGED_IN;
                }
            }
            return LoginResult.TIMEOUT;
        } finally {
            closeQuietly(page);
        }
    }

    /** 未登录时把登录入口点出来，省得用户自己找 */
    private void guideToLoginPage(Page page) {
        try {
            Locator noLogin = page.locator(NO_LOGIN_ANCHOR).first();
            if (noLogin.count() > 0) {
                noLogin.click(new Locator.ClickOptions()
                        .setTimeout(ACTION_TIMEOUT_MS).setForce(true));
                sleep(500);
            }
        } catch (Exception e) {
            log.debug("引导到登录入口失败（{}），用户也可自己点", e.getMessage());
        }
    }

    /**
     * 当前页面是否处于未登录状态。
     * <p>
     * 只看"登录/注册"入口在不在：登录后智联会把它换成用户头像节点，
     * 这个变化比任何 cookie 都可靠。
     */
    public boolean isLoggedOut(Page page) {
        try {
            return page.locator(NO_LOGIN_ANCHOR).count() > 0;
        } catch (Exception e) {
            // 页面还没加载完时按"未登录"处理，让上层继续等
            return true;
        }
    }

    /** 诊断用：当前页面顶栏的 cookie 名快照（确认登录态落在哪个字段上） */
    public List<String> loginCookieNames() {
        try {
            return browserManager.context().cookies(DOMAIN).stream()
                    .map(c -> c.name).sorted().toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // 岗位采集
    // ------------------------------------------------------------------

    /**
     * 处理一个关键词：进搜索页 → 填关键词按 Enter → 逐页翻 →
     * 每张卡片回调一次（回调里可以投递，页面还停在这张卡的详情面板上）。
     *
     * 智联的翻页不是"加载下一页"，是整页替换：点"下一页"后原来那些
     * {@code div.job-card} 节点会被换掉，所以每张卡都要<b>重新按下标取</b>，
     * 不能缓存 Locator。
     */
    public void processKeyword(String searchUrl, String keyword, int maxCards,
                               ProgressListener listener, BooleanSupplier stop,
                               CardConsumer<ZhilianJobCard> consumer) {
        Page page = browserManager.context().newPage();
        configureTimeouts(page);
        // 点投递可能新开标签页，先装好关门器再开始点
        page.context().onPage(ZhilianDriver::closeQuietly);
        try {
            navigate(page, searchUrl, listener);
            if (!waitForCards(page, listener)) {
                listener.onProgress("【" + keyword + "】岗位列表未加载出来（可能被登录或验证挡住），跳过");
                return;
            }

            int processed = 0;
            // 上一张卡的面板链接，用来判断新面板有没有真的换过来
            String lastJobUrl = null;
            for (int pageIndex = 1; pageIndex <= MAX_PAGE; pageIndex++) {
                if (stop.getAsBoolean()) {
                    listener.onProgress("收到停止指令，中断采集（已处理 " + processed + " 个）");
                    break;
                }
                int count = page.locator(CARD_SELECTOR).count();
                listener.onProgress("【" + keyword + "】第 " + pageIndex + " 页 " + count + " 个岗位");
                for (int i = 0; i < count; i++) {
                    if (stop.getAsBoolean() || processed >= maxCards) {
                        break;
                    }
                    try {
                        // 翻页是整页替换，下标要重新取
                        Locator card = page.locator(CARD_SELECTOR).nth(i);
                        if (card.count() == 0) {
                            break;
                        }
                        // 卡片本身不带 jobId（链接全是公司页），必须点开详情面板才拿得到
                        ZhilianJobCard jobCard = readCardFromPanel(page, card, lastJobUrl);
                        if (jobCard == null || jobCard.getJobId() == null) {
                            log.debug("【{}】第 {} 张卡取不到 jobId，跳过", keyword, i + 1);
                            continue;
                        }
                        lastJobUrl = jobCard.getJobUrl();
                        currentCardIndex = i;
                        processed++;
                        consumer.accept(jobCard, page);
                    } catch (Exception e) {
                        log.debug("【{}】处理第 {} 张卡异常: {}", keyword, i + 1, e.getMessage());
                    }
                }
                if (processed >= maxCards) {
                    listener.onProgress("【" + keyword + "】已达单关键词上限 " + maxCards + " 个");
                    break;
                }
                if (isNextDisabled(page)) {
                    listener.onProgress("【" + keyword + "】已到最后一页");
                    break;
                }
                if (!clickNextPage(page)) {
                    listener.onProgress("【" + keyword + "】翻页失败，结束该关键词");
                    break;
                }
                if (!waitForCards(page, null)) {
                    listener.onProgress("【" + keyword + "】翻页后列表未加载，结束该关键词");
                    break;
                }
            }
            listener.onProgress("【" + keyword + "】处理完成，共 " + processed + " 个");
        } finally {
            currentCardIndex = -1;
            closeQuietly(page);
        }
    }

    /**
     * 点开一张卡片的详情面板，从面板里读全部字段。
     * <p>
     * <b>为什么必须点</b>：智联列表卡片的 {@code a[href]} 全是公司页
     * （{@code /companydetail/CZL....htm}），职位链接只存在于详情面板的
     * {@code a[href*='jobdetail/']} 上，jobId 就在那段里
     * （{@code CCL1254044340J40897050612}）。卡片本身没有任何 data 属性带 id
     * （实测扫过全部 data-* 属性，一个都没有）。
     * <p>
     * <b>面板是异步拉的，读完必须确认是这张卡的</b>：面板 DOM 是复用的，
     * 点下去之后旧内容还在，标题节点也一直在，{@code waitFor} 立刻返回。
     * 所以拿列表卡上的标题对一遍，并且要求 jobdetail 链接和上一张不同——
     * 两个条件都满足才认。实测不这么做会读到上一张甚至下一张的面板，
     * jobId 错=去重错=真实模式下投错职位。
     */
    private ZhilianJobCard readCardFromPanel(Page page, Locator card, String previousJobUrl) {
        String expectTitle = safeText(card.locator("span.vue-clamp__text").first());
        // 上一张卡的遮罩/打招呼弹窗会吃掉这次点击
        bruteRemoveMasks(page);
        card.click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS).setForce(true));
        Locator panel = page.locator(PANEL_SELECTOR).first();
        if (panel.count() == 0) {
            return null;
        }

        // 轮询到面板和这张卡对上号
        Locator titleLoc = panel.locator(".job-detail-summary__title-text").first();
        Locator linkLoc = panel.locator("a[href*='jobdetail/']").first();
        String jobUrl = null;
        long deadline = System.currentTimeMillis() + ACTION_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String shown = safeText(titleLoc);
            String href = null;
            try {
                if (linkLoc.count() > 0) {
                    href = linkLoc.getAttribute("href");
                }
            } catch (Exception ignore) {
                href = null;
            }
            boolean titleOk = expectTitle == null || expectTitle.isBlank()
                    || expectTitle.equals(shown);
            boolean fresh = href != null && !href.equals(previousJobUrl);
            if (titleOk && fresh) {
                jobUrl = href;
                break;
            }
            page.waitForTimeout(150);
        }
        if (jobUrl == null) {
            log.debug("面板没和卡片对上号，跳过（期望标题={}）", expectTitle);
            return null;
        }
        if (jobUrl.startsWith("/")) {
            jobUrl = DOMAIN + jobUrl;
        }

        ZhilianJobCard jobCard = new ZhilianJobCard();
        jobCard.setJobId(ZhilianJobCard.extractJobId(jobUrl));
        jobCard.setJobUrl(jobUrl);
        jobCard.setJobName(safeText(titleLoc));
        jobCard.setSalaryDesc(safeText(panel.locator(".job-detail-summary__salary").first()));
        jobCard.setBrandName(safeText(panel.locator(
                ".job-detail-summary__company-name, .job-company-info__name").first()));
        // 公司 meta 实测两种形态：
        //   "/100-299人 · 鞋服箱包批发/零售/贸易"
        //   "/未融资 · 20-99人 · 广告/公关/营销、会议/展览/活动"
        splitCompanyMeta(jobCard, safeText(panel.locator(".job-detail-summary__company-meta").first()));
        jobCard.setBossName(safeText(panel.locator(".job-detail-publisher__name").first()));
        jobCard.setBossTitle(safeText(panel.locator(".job-detail-publisher__job-title").first()));
        jobCard.setPostDescription(safeText(panel.locator("div.job-description__content").first()));

        // 地区/经验/学历/招聘人数在 ul.job-detail-summary__tags 里，一个 li 一项
        List<String> facts = new ArrayList<>();
        for (Locator li : panel.locator(".job-detail-summary__tags > li, "
                + ".job-detail-summary__tags > span").all()) {
            String t = safeText(li);
            if (t != null && !t.isBlank() && t.length() <= 14) {
                facts.add(t);
            }
        }
        applySummaryFacts(jobCard, facts);
        jobCard.setLocationRaw(facts.isEmpty() ? null : facts.get(0));
        ZhilianJobCard.splitLocation(jobCard);
        return jobCard;
    }

    /**
     * 从面板 {@code ul.job-detail-summary__tags} 的 li 里认字段。
     * <p>
     * 顺序实测是 地区 → 经验 → 学历 → 招N人，但经验和学历都可能缺席
     * （"学历不限"也是学历的一种），所以按内容特征认而不是死背下标：
     * 带"年"的是经验，带学历关键词的是学历，"招"开头的是招聘人数。
     * 地区由调用方取第 0 项。
     */
    static void applySummaryFacts(ZhilianJobCard card, List<String> facts) {
        if (card == null || facts == null) {
            return;
        }
        for (String fact : facts) {
            if (fact == null) {
                continue;
            }
            String t = fact.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("招")) {
                continue;
            }
            if (t.contains("年")) {
                card.setJobExperience(t);
            } else if (DEGREE_WORDS.stream().anyMatch(t::contains)) {
                card.setJobDegree(t);
            }
        }
    }

    /**
     * 公司 meta 拆规模和行业。
     * <p>
     * 形态不固定：可能是两段（规模 · 行业），也可能是三段
     * （融资阶段 · 规模 · 行业），行业自己还带顿号。所以规模按"含人"认，
     * 行业取最后一段——行业永远在末尾。
     */
    static void splitCompanyMeta(ZhilianJobCard card, String meta) {
        if (card == null || meta == null || meta.isBlank()) {
            return;
        }
        String[] parts = meta.split("[·\\|]");
        for (String part : parts) {
            String p = part.trim().replaceAll("^[/\\s]+", "");
            if (p.contains("人")) {
                card.setBrandScaleName(p);
            }
        }
        String last = parts[parts.length - 1].trim();
        if (!last.isEmpty() && !last.contains("人")) {
            card.setIndustryName(last);
        }
    }

    // ------------------------------------------------------------------
    // 投递
    // ------------------------------------------------------------------

    /**
     * 投递一个岗位：点卡片出详情面板 → 点"立即投递" → 处理简历选择框 →
     * 点"投递简历"确认 → 清遮罩。
     * <p>
     * 预演模式只走"点卡片出面板"这一步就返回——不点投递，但流程走到了，
     * 能提前发现"这个岗位没有投递入口"这类问题。
     */
    public DeliveryOutcome deliver(ZhilianJobCard card, Page listPage,
                                   ZhilianProperties.ZhilianConfig config,
                                   ProgressListener listener, BooleanSupplier stop) {
        String target = (card.getBrandName() != null ? card.getBrandName() : "?")
                + " | " + (card.getJobName() != null ? card.getJobName() : "?");
        int index = currentCardIndex;
        if (index < 0) {
            return DeliveryOutcome.failed("卡片下标丢失，无法定位岗位");
        }
        if (stop.getAsBoolean()) {
            return DeliveryOutcome.failed("已停止");
        }
        try {
            // 每张卡点之前都清一次：上一张卡的遮罩/打招呼弹窗会吃掉这次点击
            bruteRemoveMasks(listPage);
            Locator cardLocator = listPage.locator(CARD_SELECTOR).nth(index);
            if (cardLocator.count() == 0) {
                return DeliveryOutcome.failed("卡片已不在列表里（页面可能刷新过）");
            }
            cardLocator.click(new Locator.ClickOptions()
                    .setTimeout(ACTION_TIMEOUT_MS).setForce(true));
            // 面板 DOM 是复用的，点完不会立刻换。等到链接变成这张卡的再动手，
            // 否则"立即投递"点到上一张的面板上，真实模式就是投错职位
            if (!waitForPanelOf(listPage, card.getJobUrl())) {
                bruteRemoveMasks(listPage);
                return DeliveryOutcome.failed("详情面板没能切换到目标岗位，放弃投递（避免投错）");
            }

            if (config.isDryRun()) {
                listener.onProgress("预演：跳过真实投递 | " + target);
                bruteRemoveMasks(listPage);
                return DeliveryOutcome.preview(config.getSayHi());
            }

            Locator applyButton = listPage.locator(APPLY_BUTTON).first();
            if (applyButton.count() == 0) {
                bruteRemoveMasks(listPage);
                return DeliveryOutcome.failed("详情面板里没有投递入口");
            }
            String buttonText = safeText(applyButton);
            if (buttonText != null && (buttonText.contains("已投递")
                    || buttonText.contains("继续沟通") || buttonText.contains("已沟通"))) {
                bruteRemoveMasks(listPage);
                return new DeliveryOutcome(DeliveryStatus.DELIVERED, null, null);
            }
            bruteRemoveMasks(listPage);
            applyButton.click(new Locator.ClickOptions()
                    .setTimeout(ACTION_TIMEOUT_MS).setForce(true));
            sleep(800);

            // 上限提示只在投递动作后出现，先探再走后面的确认流程
            if (hasReachedLimit(listPage)) {
                bruteRemoveMasks(listPage);
                return DeliveryOutcome.limit("触发每日投递上限");
            }
            handleResumeDialog(listPage);
            confirmResume(listPage);
            sleep(600);
            closeGreetingModal(listPage);
            bruteRemoveMasks(listPage);

            if (hasReachedLimit(listPage)) {
                return DeliveryOutcome.limit("触发每日投递上限");
            }
            listener.onProgress("已投递 | " + target);
            return DeliveryOutcome.delivered(config.getSayHi());
        } catch (Exception e) {
            log.warn("投递过程异常 | {}: {}", target, e.getMessage());
            bruteRemoveMasks(listPage);
            return DeliveryOutcome.failed(e.getMessage());
        }
    }

    /**
     * 等到详情面板的 jobdetail 链接变成 {@code expectedJobUrl}。
     * <p>
     * 面板是复用同一个 DOM 节点异步换内容的，点完卡片之后旧链接还在，
     * 靠固定 sleep 赌 timing 不可靠。轮询到对得上才返回 true。
     */
    private boolean waitForPanelOf(Page page, String expectedJobUrl) {
        if (expectedJobUrl == null || expectedJobUrl.isBlank()) {
            return true;
        }
        Locator linkLoc = page.locator(PANEL_SELECTOR + " a[href*='jobdetail/']").first();
        long deadline = System.currentTimeMillis() + ACTION_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (linkLoc.count() > 0 && expectedJobUrl.equals(linkLoc.getAttribute("href"))) {
                    return true;
                }
            } catch (Exception ignore) {
                // 节点正在被替换，下一次轮询再试
            }
            page.waitForTimeout(150);
        }
        return false;
    }

    /** "请选择要投递的简历"框：勾上"每次投递默认发送该简历"，省得每次弹 */
    private void handleResumeDialog(Page page) {
        try {
            Locator check = page.locator(DEFAULT_RESUME).first();
            if (check.count() > 0 && check.isVisible()) {
                check.click(new Locator.ClickOptions()
                        .setTimeout(ACTION_TIMEOUT_MS).setForce(true));
                sleep(300);
            }
        } catch (Exception e) {
            log.debug("勾选默认简历失败（{}），可能没有这个弹窗", e.getMessage());
        }
    }

    /**
     * 点"投递简历"确认。
     * <p>
     * 按钮文案可能被包在好几层 span 里，Playwright 的 :has-text 会命中祖先节点
     * 导致点到遮罩，所以先用选择器试，失败再用 JS 找<b>自身 innerText 精确等于</b>
     * "投递简历"的最后一个节点——最后一个是真正可点的那个。
     */
    private void confirmResume(Page page) {
        try {
            Locator confirm = page.locator(CONFIRM_BUTTON).first();
            if (confirm.count() > 0 && confirm.isVisible()) {
                confirm.click(new Locator.ClickOptions()
                        .setTimeout(ACTION_TIMEOUT_MS).setForce(true));
                return;
            }
        } catch (Exception e) {
            log.debug("选择器点确认按钮失败，走 JS 兜底: {}", e.getMessage());
        }
        try {
            page.evaluate("() => {" +
                    "  const nodes = Array.from(document.querySelectorAll('button, div, span, a'))" +
                    "    .filter(b => b.innerText && b.innerText.trim() === '投递简历');" +
                    "  if (nodes.length > 0) nodes[nodes.length - 1].click();" +
                    "}");
        } catch (Exception e) {
            log.debug("JS 兜底点确认按钮也失败: {}", e.getMessage());
        }
    }

    /** 关掉投递后置的"向HR打招呼/投递成功"弹窗 */
    private void closeGreetingModal(Page page) {
        try {
            Locator close = page.locator(GREETING_CLOSE).first();
            if (close.count() > 0 && close.isVisible()) {
                close.click(new Locator.ClickOptions()
                        .setTimeout(ACTION_TIMEOUT_MS).setForce(true));
                sleep(300);
            }
        } catch (Exception e) {
            log.debug("关闭后置弹窗失败（{}）", e.getMessage());
        }
    }

    /**
     * JS 暴力 remove 一切遮罩。
     * <p>
     * 智联的遮罩是 React 渲染的，点关闭按钮经常点不中（节点被替换），
     * 直接删 DOM 最省事。删掉后 React 状态可能还在，但视觉上已经能点了。
     */
    private void bruteRemoveMasks(Page page) {
        try {
            page.evaluate("() => {" +
                    "  const sels = ['.deliver-greeting-modal', '.deliver-greeting-modal__mask'," +
                    "    '.el-overlay', '.v-modal', '.van-overlay', '[class*=\"modal-mask\"]," +
                    "    '[class*=\"backdrop\"], [class*=\"mask\"]'];" +
                    "  for (const s of sels) {" +
                    "    document.querySelectorAll(s).forEach(el => {" +
                    "      const st = getComputedStyle(el);" +
                    "      if (st && (st.position === 'fixed' || st.position === 'absolute')" +
                    "          && el.offsetHeight > 100) { el.remove(); }" +
                    "    });" +
                    "  }" +
                    "}");
        } catch (Exception e) {
            log.debug("清理遮罩失败: {}", e.getMessage());
        }
    }

    /** 今日投递上限。命中就必须停，继续点只会全量失败 */
    public boolean hasReachedLimit(Page page) {
        try {
            Locator workflow = page.locator("//div[@class='a-job-apply-workflow']");
            if (workflow.count() > 0) {
                String text = workflow.first().textContent();
                if (text != null && text.contains("达到上限")) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 翻页
    // ------------------------------------------------------------------

    /** 末页判据："下一页"按钮 class 含 soupager__btn--disable */
    private boolean isNextDisabled(Page page) {
        try {
            Locator next = page.locator(NEXT_BUTTON).first();
            if (next.count() == 0) {
                return true;
            }
            String cls = next.getAttribute("class");
            if (cls != null && cls.contains(NEXT_DISABLED_CLASS)) {
                return true;
            }
            String disabled = next.getAttribute("disabled");
            return disabled != null && ("disabled".equalsIgnoreCase(disabled)
                    || "true".equalsIgnoreCase(disabled));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean clickNextPage(Page page) {
        try {
            Locator next = page.locator(NEXT_BUTTON).first();
            if (next.count() == 0) {
                return false;
            }
            next.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions()
                    .setTimeout(ACTION_TIMEOUT_MS));
            next.click(new Locator.ClickOptions()
                    .setTimeout(ACTION_TIMEOUT_MS).setForce(true));
            sleep(1500);
            return true;
        } catch (Exception e) {
            log.debug("点击下一页失败: {}", e.getMessage());
            return false;
        }
    }

    private boolean waitForCards(Page page, ProgressListener listener) {
        for (int i = 0; i < 15; i++) {
            try {
                if (page.locator(CARD_SELECTOR).count() > 0) {
                    return true;
                }
                if (page.locator(LIST_CONTAINER).count() > 0) {
                    // 列表容器出来了但还没卡片：可能是空结果，再等一轮
                    sleep(500);
                }
            } catch (Exception ignore) {
            }
            sleep(1000);
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

    private static String safeText(Locator locator) {
        try {
            if (locator.count() == 0) {
                return null;
            }
            String text = locator.textContent(new Locator.TextContentOptions()
                    .setTimeout(ACTION_TIMEOUT_MS));
            if (text == null || text.isBlank()) {
                return null;
            }
            return text.trim();
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

    public Path screenshot(Page page, String name) {
        try {
            Path dir = com.jobpilot.system.SystemPaths.dataDir().resolve("screenshots");
            java.nio.file.Files.createDirectories(dir);
            Path target = dir.resolve(name + "-" + Instant.now().toString().replace(":", "-") + ".png");
            page.screenshot(new Page.ScreenshotOptions().setPath(target).setFullPage(false));
            return target;
        } catch (Exception e) {
            log.debug("截图失败: {}", e.getMessage());
            return null;
        }
    }
}
