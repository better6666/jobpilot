package com.jobpilot.liepin;

import com.jobpilot.browser.BrowserManager;
import com.jobpilot.browser.HumanVerification;
import com.jobpilot.delivery.CardConsumer;
import com.jobpilot.delivery.DeliveryOutcome;
import com.jobpilot.delivery.DeliveryStatus;
import com.jobpilot.delivery.LoginResult;
import com.jobpilot.delivery.ProgressListener;
import com.jobpilot.delivery.PlatformConfig;
import com.jobpilot.resume.ResumeAttachmentService;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.impl.TargetClosedError;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * 猎聘浏览器操作层。
 * <p>
 * <b>线程约束：所有方法都必须在 BrowserManager 的 dispatcher 线程里调用</b>
 * （即 submit/submitAsync 的任务体内）。Playwright 客户端非线程安全。
 * <p>
 * 已知猎聘的坑（都来自实测，改这段代码前先读）：
 * <ul>
 *   <li>搜索页有多页时用 AntD 服务端分页：翻页点击
 *       {@code li.ant-pagination-next} 里的 {@code button.ant-pagination-item-link}，
 *       改 URL 的 currentPage 没用</li>
 *   <li>结构化数据来自拦截 {@code api-c.liepin.com/.../pc-search-job}，
 *       <b>必须排除同前缀的 pc-search-job-cond-init</b>（筛选项初始化，拦了会
 *       白白等一次响应）</li>
 *   <li>搜索结果以可见卡片为准，优先按岗位编号关联接口数据。卡片未暴露编号时，
 *       仅在当前页卡片数与接口实体数一致的情况下按顺序关联</li>
 *   <li>沟通按钮要 hover HR 区才渲染，不 hover 就整张卡被静默跳过</li>
 *   <li>卡片里同时有收藏/分享等 round 按钮，<b>只能按自身文本"聊一聊"判定</b>；
 *       "继续聊"是已投递状态，点了只会重开旧会话</li>
 *   <li>点"聊一聊"后平台自动发出默认招呼语（"对这个岗位充满期待"），聊天以页内
 *       IM 浮层打开，<b>没有确认对话框、不开新标签页</b>——Boss 那套确认框逻辑
 *       这里不需要。同时还会开一个"我的沟通"抽屉，压在卡片右半边，两个都得关</li>
 *   <li>IM 组件 2026-09 起是 ant-im/im-ui（{@code im-ui-basic-chat-wrapper} 等），
 *       老前缀 {@code __im_basic__*} 整体失效。<b>选择器失配不会报错，只会静默
 *       什么都发不出去</b>，所以追问是否落地必须按消息文本核对</li>
 *   <li>"完善简历"弹窗和订阅弹窗会遮罩吃掉所有点击，每张卡、每次翻页前都要关</li>
 *   <li>列表页在受控标签页会长时间转圈：每个 evaluate/locator 都显式超时，
 *       导航用 DOMCONTENTLOADED 而不是 LOAD/NETWORKIDLE</li>
 * </ul>
 */
@Slf4j
@Component
public class LiepinDriver {

    private final BrowserManager browserManager;
    private final ResumeAttachmentService resumes;

    public LiepinDriver(BrowserManager browserManager) {
        this(browserManager, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LiepinDriver(BrowserManager browserManager, ResumeAttachmentService resumes) {
        this.browserManager = browserManager;
        this.resumes = resumes;
    }

    public String validateResume(PlatformConfig config) {
        return !config.isSendResume() ? null : resumes == null ? "当前运行未启用简历附件模块" : resumes.validate(config);
    }

    private static final String DOMAIN = "https://www.liepin.com";
    private static final String LOGIN_URL = "https://www.liepin.com/login";
    /** 登录令牌。快照里 liepin_login_valid 的值可能是 "0"，不能作判据 */
    private static final String LOGIN_COOKIE = "lt_auth";
    /** 猎聘 2026-09 改版后岗位卡片类名从 kebab-case 变成 camelCase，
     *  前面拼了 CSS module 哈希（如 _40108OjE3M.jobCardPcContainer）。
     *  用 class*='jobCardPcContainer' 部分匹配绕过哈希，
     *  不能用 data-nick——那是岗位标题链接不是卡片容器，
     *  选了它的话 card.locator("聊一聊按钮") 就找不到了 */
    private static final String CARD_SELECTOR = "[class*='jobCardPcContainer']:visible";
    private static final String PAGINATION_BOX = ".list-pagination-box";
    private static final Pattern NO_JOBS = Pattern.compile(
            "^(暂无相关职位|暂无符合条件的职位|没有找到相关职位|未找到相关职位|暂无相关岗位)[，。！!\\s]*$");
    /**
     * 聊天窗容器。2026-09 实测：猎聘把整套 IM 换成 ant-im/im-ui 组件，
     * 老的 {@code __im_basic__*} 前缀一个都不剩（选择器留个兜底，平台回滚时不至于全瞎）。
     * <b>这个选择器失配过一次，代价是当天 36 条投递全部无法核对。</b>
     */
    private static final String CHAT_WINDOW = ".im-ui-basic-chat-wrapper, .__im_basic__header-wrap";
    /** 输入框：placeholder 是「请输入文字，按Enter键发送」的 textarea */
    private static final String CHAT_INPUT = ".im-ui-chat-input textarea";
    /** 自己这一方发出去的消息气泡，用来确认追问真的落地 */
    private static final String SENT_MESSAGE = ".im-ui-message-item-send";
    /** 聊天窗关闭按钮（ant-im 的 modal），老的 contacts-title svg 留作兜底 */
    private static final String CHAT_CLOSE = ".ant-im-modal-close, div.__im_basic__contacts-title svg";
    /** 点"聊一聊"后同时会开的"我的沟通"抽屉，不关掉会一直叠在页面上 */
    private static final String CHAT_DRAWER = ".ant-im-drawer";
    private static final String DRAWER_CLOSE = ".ant-im-drawer-close";
    private static final String SEARCH_API = "com.liepin.searchfront4c.pc-search-job";
    private static final int NAV_TIMEOUT_MS = 45_000;
    private static final int ACTION_TIMEOUT_MS = 10_000;
    /** 等一次搜索接口响应：刚启动的浏览器首屏慢，比普通操作放宽 */
    private static final int RESPONSE_TIMEOUT_MS = 20_000;
    private static final int MAX_PAGE = 50;

    /**
     * 当前正在处理的卡片下标。
     * <p>
     * 采集阶段和投递阶段是分开的两次调用（编排层要在中间做去重和过滤），
     * 而猎聘列表页没有"选中态"这个概念，投递时只能靠下标找回那张卡。
     * 整个流程跑在单线程 dispatcher 上，collect 里设好值、deliver 里立刻用，
     * 不存在竞争。
     */
    private volatile int currentCardIndex = -1;

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------

    /** 打开列表页并等待登录。已登录（profile 里有 cookie）直接返回。 */
    public LoginResult ensureLogin(int timeoutMinutes, ProgressListener listener, BooleanSupplier stop) {
        Page page = browserManager.context().newPage();
        configureTimeouts(page);
        try {
            navigate(page, DOMAIN, listener);
            if (hasLoginCookie()) {
                listener.onProgress("已检测到猎聘登录态，直接进入岗位列表");
                return LoginResult.LOGGED_IN;
            }
            listener.onProgress("未检测到猎聘登录态，请在浏览器窗口里扫码登录（等待 " + timeoutMinutes + " 分钟）");
            guideToLoginPage(page);
            long deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L;
            while (System.currentTimeMillis() < deadline && !stop.getAsBoolean()) {
                sleep(2000);
                if (hasLoginCookie()) {
                    listener.onProgress("扫码登录成功");
                    return LoginResult.LOGGED_IN;
                }
            }
            return LoginResult.TIMEOUT;
        } finally {
            closeQuietly(page);
        }
    }

    /** 未登录时把登录页翻出来并切到二维码，省得用户自己找 */
    private void guideToLoginPage(Page page) {
        try {
            if (page.url() == null || !page.url().contains("/login")) {
                navigate(page, LOGIN_URL, null);
            }
            Locator qrSwitch = page.locator(".switch-type-mask-img-box");
            if (qrSwitch.count() > 0) {
                qrSwitch.first().click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
            }
        } catch (Exception e) {
            log.debug("切扫码登录失败（{}），用户也可自己点", e.getMessage());
        }
    }

    /** 当前 profile 是否已登录猎聘。cookie 第一判据，DOM 只作兜底 */
    public boolean hasLoginCookie() {
        try {
            List<String> names = browserManager.context().cookies(DOMAIN).stream()
                    .map(c -> c.name).toList();
            if (names.contains(LOGIN_COOKIE)) {
                return true;
            }
            // user_name 是登录后才会下发的，和 lt_auth 二选一都能证明登录态
            return names.contains("user_name");
        } catch (Exception e) {
            return false;
        }
    }

    /** 登录后的 cookie 名快照（诊断用：确认 profile 里的登录态字段） */
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
     * 处理一个关键词：进搜索页 → 逐页翻 → 每张卡片 hover 出沟通按钮 →
     * 回调 CardCallback（回调里可以投递，页面还停在这张卡上）。
     */
    public void processKeyword(String searchUrl, String keyword, int maxCards,
                               ProgressListener listener, BooleanSupplier stop,
                               CardConsumer<LiepinJobCard> consumer) {
        processKeyword(searchUrl, keyword, null, maxCards, listener, stop, consumer);
    }

    /**
     * @param myDegree "我的学历"。大专及以下时按它在猎聘搜索里筛（见 {@link LiepinEduLevel}）
     */
    public void processKeyword(String searchUrl, String keyword, String myDegree, int maxCards,
                               ProgressListener listener, BooleanSupplier stop,
                               CardConsumer<LiepinJobCard> consumer) {
        Page page = browserManager.context().newPage();
        configureTimeouts(page);
        // 按照浏览器当前搜索页缓存，导航或翻页前清除旧结果。
        AtomicReference<List<LiepinJobCard>> cache = new AtomicReference<>(List.of());
        boolean keepPage = false;
        AtomicReference<Map<String, String>> eduOptions = new AtomicReference<>(Map.of());
        page.onResponse(response -> {
            if (isCondInitResponse(response)) {
                Map<String, String> options = LiepinEduLevel.parseOptions(safeBody(response));
                if (!options.isEmpty()) {
                    eduOptions.set(options);
                    log.info("猎聘学历选项: {}", options);
                }
                return;
            }
            if (isSearchResponse(response)) {
                List<LiepinJobCard> cards = LiepinJobCard.parseList(safeBody(response));
                cache.set(cards);
                log.debug("【{}】拦截到 {} 个岗位实体", keyword, cards.size());
            }
        });
        try {
            navigate(page, searchUrl, listener);
            if (LiepinEduLevel.worthFiltering(myDegree) && !searchUrl.contains("eduLevel=")) {
                if (!waitForSearchResults(page, keyword, null, listener, stop)) return;
                applyEduLevel(page, searchUrl, myDegree, eduOptions, cache, keyword, listener, stop);
            }

            int processed = 0;
            String previousPage = null;
            for (int pageIndex = 1; pageIndex <= MAX_PAGE && !stop.getAsBoolean(); pageIndex++) {
                if (!waitForSearchResults(page, keyword, previousPage, listener, stop)) break;
                Locator cards = page.locator(CARD_SELECTOR);
                int count = cards.count();
                List<LiepinJobCard> entities = cache.get();
                listener.onProgress("【" + keyword + "】第 " + pageIndex + " 页 " + count + " 个岗位");
                for (int i = 0; i < count; i++) {
                    if (stop.getAsBoolean() || processed >= maxCards) {
                        break;
                    }
                    try {
                        checkSearchAccess(page, listener, stop);
                        if (stop.getAsBoolean()) break;
                        Locator card = cards.nth(i);
                        if (card.count() == 0) {
                            break;
                        }
                        dismissBlockingModals(page);
                        prepareCard(page, card, i);
                        currentCardIndex = i;
                        LiepinJobCard fromPage = fallbackCard(card);
                        LiepinJobCard jobCard = fromPage == null
                                ? (count == entities.size() ? entities.get(i) : null)
                                : entities.stream().filter(item -> fromPage.getJobId().equals(item.getJobId()))
                                        .findFirst().orElse(fromPage);
                        if (jobCard == null) {
                            log.debug("【{}】第 {} 张卡缺少可对应的岗位编号，跳过", keyword, i + 1);
                            continue;
                        }
                        processed++;
                        consumer.accept(jobCard, page);
                    } catch (HumanVerification.PausedRun | TargetClosedError e) {
                        throw e;
                    } catch (Exception e) {
                        log.debug("【{}】处理第 {} 张卡异常: {}", keyword, i + 1, e.getMessage());
                    }
                }
                if (processed >= maxCards) {
                    listener.onProgress("【" + keyword + "】已达单关键词上限 " + maxCards + " 个");
                    break;
                }
                if (stop.getAsBoolean() || pageIndex == MAX_PAGE) break;
                checkSearchAccess(page, listener, stop);
                if (stop.getAsBoolean()) break;
                previousPage = pageIdentity(cards);
                cache.set(List.of());
                if (!clickNextPage(page, listener)) {
                    checkSearchAccess(page, listener, stop);
                    break;
                }
            }
            listener.onProgress("【" + keyword + "】处理完成，共 " + processed + " 个");
        } catch (HumanVerification.PausedRun e) {
            keepPage = true;
            throw e;
        } finally {
            currentCardIndex = -1;
            if (!keepPage) closeQuietly(page);
        }
    }

    /** 用卡片里的岗位详情地址或平台记录的编号识别岗位。 */
    private LiepinJobCard fallbackCard(Locator card) {
        try {
            Object result = card.evaluate("el => {" +
                    "  const fromExt = () => {" +
                    "    const raw = el.getAttribute('data-tlg-ext');" +
                    "    if (!raw) return null;" +
                    "    try {" +
                    "      const json = JSON.parse(decodeURIComponent(raw));" +
                    "      return json.jobId ? String(json.jobId) : null;" +
                    "    } catch (e) {" +
                    "      const m = raw.match(/\\\"jobId\\\":\\\"(\\d+)\\\"/);" +
                    "      return m ? m[1] : null;" +
                    "    }" +
                    "  };" +
                    "  const fromScm = () => {" +
                    "    const raw = el.getAttribute('data-tlg-scm');" +
                    "    if (!raw) return null;" +
                    "    const m = raw.match(/jobId=(\\d+)/);" +
                    "    return m ? m[1] : null;" +
                    "  };" +
                    "  const link = el.querySelector('a[href*=\"/job/\"]');" +
                    "  if (link) {" +
                    "    const url = new URL(link.href);" +
                    "    const detail = url.pathname.match(/^[/]job[/](\\d+)[.]shtml$/);" +
                    "    if (url.hostname === 'www.liepin.com' && detail) return detail[1];" +
                    "  }" +
                    "  return fromExt() || fromScm();" +
                    "}");
            if (result == null) {
                return null;
            }
            String jobId = String.valueOf(result);
            if (jobId.isBlank() || "null".equals(jobId)) {
                return null;
            }
            LiepinJobCard card2 = new LiepinJobCard();
            card2.setJobId(jobId);
            card2.setJobName("（接口未拦到，仅 DOM 兜底）");
            return card2;
        } catch (Exception e) {
            return null;
        }
    }

    /** 滚动到视野中央 + hover HR 区，把沟通按钮hover出来 */
    private void prepareCard(Page page, Locator card, int index) {
        scrollIntoViewCenter(card);
        for (int attempt = 1; attempt <= 3; attempt++) {
            hoverRecruiterArea(card);
            Locator button = findChatButton(card);
            if (button != null) {
                return;
            }
            sleep(300);
        }
    }

    private void scrollIntoViewCenter(Locator card) {
        try {
            card.evaluate("el => el.scrollIntoView({behavior:'instant', block:'center'})");
            var box = card.boundingBox();
            if (box != null && (box.y < 0 || box.y > 2000)) {
                card.evaluate("el => el.scrollIntoView({behavior:'instant', block:'center'})");
            }
        } catch (Exception e) {
            log.debug("滚动到卡片失败: {}", e.getMessage());
        }
    }

    /**
     * hover HR 信息区触发沟通按钮渲染。
     * 选择器一级级降级：猎聘改过几版类名，最后兜底 hover 整张卡。
     */
    private void hoverRecruiterArea(Locator card) {
        String[] selectors = {
                ".recruiter-info-box",
                ".recruiter-info, .hr-info, .contact-info",
                "[class*='recruiter'], [class*='hr-'], [class*='contact']",
                ".job-card-footer, .card-footer",
                ".job-bottom, .bottom-info"
        };
        for (String selector : selectors) {
            try {
                Locator area = card.locator(selector).first();
                if (area.count() > 0) {
                    area.hover(new Locator.HoverOptions().setTimeout(5_000));
                    return;
                }
            } catch (Exception ignore) {
                // 这一级不可用就试下一级
            }
        }
        try {
            card.hover(new Locator.HoverOptions().setTimeout(5_000));
        } catch (Exception ignore) {
            // 整张卡都 hover 不动：按钮可能本来就可见，交给 findChatButton 判定
        }
    }

    /**
     * 找沟通按钮。<b>只能按自身文本判定</b>：卡片里同时有收藏、分享等
     * ant-btn-round 按钮，纯 class 选择器会命中错按钮。
     * "聊一聊"是没聊过的，"继续聊"是已投递的。
     */
    private Locator findChatButton(Locator card) {
        for (String selector : new String[]{
                "button:has-text('聊一聊')", "button:has-text('继续聊')"}) {
            try {
                Locator locator = card.locator(selector);
                for (int i = 0; i < locator.count(); i++) {
                    Locator candidate = locator.nth(i);
                    if (candidate.isVisible()) {
                        return candidate;
                    }
                }
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 投递
    // ------------------------------------------------------------------

    /**
     * 投递一个岗位：hover 出沟通按钮 → 点"聊一聊" → 等聊天窗 → 补一句追问
     * 并核对它真的发出去了 → 关聊天窗和沟通抽屉。
     * <p>
     * <b>成功判据是聊天窗出现</b>。点"聊一聊"时平台会把默认招呼语（"对这个岗位
     * 充满期待"）直接发给 HR，聊天窗就是这件事的回执；聊天窗没出现说明点击被
     * 弹窗吃掉、登录态掉了或被风控，那种情况下记"已投递"就是虚报——
     * 2026-09-27 实测：选择器失配后连续 36 次都判成成功，用户对着猎聘只看到 4 个会话。
     * <p>
     * {@code greeting} 是调用方（编排层）已经定好的一句追问：AI 开着时按 JD 生成，
     * 关了就是配置里的固定话术。这里不再自己读配置，避免同一句话在采集和投递之间
     * 被改来改去。追问没发出去不影响"已沟通"（默认招呼语已经到位），但落库时
     * 不能把它当成已发送的话术记进去。
     */
    public DeliveryOutcome deliver(LiepinJobCard card, Page listPage, LiepinProperties.LiepinConfig config,
                                   String greeting, ProgressListener listener, BooleanSupplier stop) {
        String target = (card.getBrandName() != null ? card.getBrandName() : "?")
                + " | " + (card.getJobName() != null ? card.getJobName() : "?");
        int index = currentCardIndex;
        if (index < 0) {
            return DeliveryOutcome.failed("卡片下标丢失，无法定位沟通按钮");
        }
        if (config.isDryRun()) {
            listener.onProgress("预演：跳过真实投递 | " + target);
            return DeliveryOutcome.preview(greeting);
        }
        if (stop.getAsBoolean()) {
            return DeliveryOutcome.failed("已停止");
        }
        try {
            Locator cardLocator = listPage.locator(CARD_SELECTOR).nth(index);
            if (cardLocator.count() == 0) {
                return DeliveryOutcome.failed("卡片已不在列表里（页面可能刷新过）");
            }
            LiepinJobCard visibleCard = fallbackCard(cardLocator);
            if (visibleCard != null && !visibleCard.getJobId().equals(card.getJobId())) {
                return DeliveryOutcome.failed("岗位列表已变化，请核对后再投递");
            }
            prepareCard(listPage, cardLocator, index);
            Locator button = findChatButton(cardLocator);
            if (button == null) {
                return DeliveryOutcome.failed("未找到沟通按钮");
            }
            String text = safeText(button);
            if (text != null && text.contains("继续聊")) {
                // 已经聊过了：平台侧就是已投递，不重复点
                listener.onProgress("岗位此前已沟通过 | " + target);
                return new DeliveryOutcome(DeliveryStatus.DELIVERED, null, null);
            }
            dismissBlockingModals(listPage);
            clickWithHoverWake(button);

            if (!waitForChatWindow(listPage, stop)) {
                closeChatPanels(listPage, listener);
                log.info("聊天窗未出现，沟通未确认 | {}", target);
                return DeliveryOutcome.failed("点击后聊天窗没有出现，沟通未确认（可能被弹窗挡住或平台限制）");
            }
            String sent = null;
            if (greeting != null && !greeting.isBlank()) {
                sent = sendFollowUp(listPage, greeting, stop) ? greeting : null;
                if (sent == null) {
                    listener.onProgress("追问没发出去 | " + target);
                }
            }
            String note = config.isSendResume() ? resumes.send("liepin", listPage, config, card, stop) : null;
            if (note != null) listener.onProgress(note + " | " + target);
            closeChatPanels(listPage, listener);
            listener.onProgress("已打招呼 | " + target);
            return new DeliveryOutcome(DeliveryStatus.DELIVERED, note, sent);
        } catch (Exception e) {
            log.warn("投递过程异常 | {}: {}", target, e.getMessage());
            return DeliveryOutcome.failed(e.getMessage());
        }
    }

    /**
     * 点击前把鼠标在按钮上微移一圈。
     * hover 态有时不稳，直接 click 会点空；中心→+2px→-2px→中心能把 hover 重新激活。
     */
    private void clickWithHoverWake(Locator button) {
        try {
            button.hover(new Locator.HoverOptions().setTimeout(ACTION_TIMEOUT_MS));
            var box = button.boundingBox();
            if (box != null) {
                double cx = box.x + box.width / 2;
                double cy = box.y + box.height / 2;
                button.page().mouse().move(cx, cy);
                sleep(50);
                button.page().mouse().move(cx + 2, cy);
                sleep(50);
                button.page().mouse().move(cx - 2, cy);
                sleep(50);
                button.page().mouse().move(cx, cy);
                sleep(50);
            }
        } catch (Exception ignore) {
            // 微移失败不影响点击
        }
        button.click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
    }

    /** 等聊天窗出现。用轮询而不是 waitForSelector：本平台后者不可靠 */
    private boolean waitForChatWindow(Page page, BooleanSupplier stop) {
        for (int i = 0; i < 16 && !stop.getAsBoolean(); i++) {
            try {
                if (page.locator(CHAT_WINDOW).count() > 0) {
                    return true;
                }
            } catch (Exception ignore) {
            }
            sleep(500);
        }
        return false;
    }

    /**
     * 往聊天里补一句追问，并回来这一句到底有没有发出去。
     *
     * <p>必须核对：聊天窗里已经有平台自己发的那条默认招呼语，"消息条数变多"不能
     * 证明是我们的追问变多了，所以按文本比对最新几条自己发的消息。
     * 2026-09-27 之前这里的选择器整体失配，追问一条都没发出去，日志里却全是"已打招呼"。
     */
    private boolean sendFollowUp(Page page, String message, BooleanSupplier stop) {
        try {
            Locator input = null;
            for (int i = 0; i < 6 && input == null && !stop.getAsBoolean(); i++) {
                Locator candidate = page.locator(CHAT_INPUT);
                if (candidate.count() > 0 && candidate.first().isVisible()) {
                    input = candidate.first();
                } else {
                    sleep(500);
                }
            }
            if (input == null) {
                log.info("聊天窗里没有输入框，追问发不出去");
                return false;
            }
            input.click();
            // CHAT_INPUT 只命中 textarea（im-ui 的输入框），fill 就够了
            input.fill(message);
            sleep(1000);
            input.press("Enter");
            return sentMessageAppeared(page, message, stop);
        } catch (Exception e) {
            log.info("追问发送失败: {}", e.getMessage());
            return false;
        }
    }

    /** 追问是否出现在"我发的消息"气泡里（平台默认招呼语也是自己发的，所以只能比文本） */
    private boolean sentMessageAppeared(Page page, String message, BooleanSupplier stop) {
        String probe = normalizeForCompare(message);
        if (probe.isEmpty()) {
            return false;
        }
        for (int i = 0; i < 12 && !stop.getAsBoolean(); i++) {
            try {
                List<String> mine = page.locator(SENT_MESSAGE).allTextContents();
                for (String text : mine) {
                    if (normalizeForCompare(text).contains(probe)) {
                        return true;
                    }
                }
            } catch (Exception ignore) {
                // 列表重渲染的瞬间读不到，下一轮再试
            }
            sleep(500);
        }
        return false;
    }

    /** 比对消息文本时去掉空白和标点，只比文字 */
    static String normalizeForCompare(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\s\\p{Punct}，。！？、；：“”‘’（）【】《》]", "");
    }

    /**
     * 关掉聊天窗和"我的沟通"抽屉。
     *
     * <p>两个都要关：抽屉贴在页面右侧，正好压在岗位卡片的沟通按钮上，
     * 不关的话后面每张卡都报"未找到沟通按钮"。
     *
     * <p>这里只点平台自己的关闭按钮，不再像以前那样用 JS 直接删 DOM 节点——
     * 一是那段拼接里的转义写错，生成的 JS 从来没跑起来过（日志里每次都是
     * SyntaxError），二是把 React 管理的节点硬摘掉会让组件状态错乱，
     * 表现就是"投两个之后开始全失败"。
     */
    private void closeChatPanels(Page page, ProgressListener listener) {
        clickQuietly(page, CHAT_CLOSE);
        clickQuietly(page, DRAWER_CLOSE);
        try {
            page.keyboard().press("Escape");
        } catch (Exception ignore) {
        }
        sleep(500);
        try {
            int left = page.locator(CHAT_WINDOW).count() + page.locator(CHAT_DRAWER).count();
            if (left > 0) {
                listener.onProgress("聊天窗没关掉，可能影响后面几张卡");
                log.info("投递后仍有 IM 浮层没关掉: {} 个", left);
            }
        } catch (Exception ignore) {
            // 页面正在跳转，读不到就算了
        }
    }

    private void clickQuietly(Page page, String selector) {
        try {
            Locator close = page.locator(selector);
            if (close.count() > 0 && close.first().isVisible()) {
                close.first().click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
            }
        } catch (Exception e) {
            log.debug("点 {} 失败: {}", selector, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 分页
    // ------------------------------------------------------------------

    /** 等岗位列表渲染。只在翻页后要求卡片内容与上一页不同。 */
    private boolean waitForSearchResults(Page page, String keyword, String previousPage,
                                         ProgressListener listener, BooleanSupplier stop) {
        long deadline = System.nanoTime() + RESPONSE_TIMEOUT_MS * 1_000_000L;
        while (!stop.getAsBoolean()) {
            if (checkSearchAccess(page, listener, stop)) {
                deadline = System.nanoTime() + RESPONSE_TIMEOUT_MS * 1_000_000L;
            }
            if (stop.getAsBoolean()) return false;
            Locator cards = page.locator(CARD_SELECTOR);
            if (cards.count() > 0) {
                if (previousPage == null || !previousPage.equals(pageIdentity(cards))) return true;
            } else if (page.getByText(NO_JOBS).filter(new Locator.FilterOptions().setVisible(true)).count() > 0) {
                listener.onProgress("【" + keyword + "】猎聘当前筛选条件下无匹配岗位");
                return false;
            }
            if (System.nanoTime() >= deadline) {
                listener.onProgress("【" + keyword + "】"
                        + (previousPage == null ? "未读取到猎聘岗位列表，请检查浏览器里的搜索页"
                        : "翻页后未读取到新岗位，已停止本关键词"));
                return false;
            }
            // 由 Playwright 等待，以便处理 SPA 渲染期间到达的网络事件。
            page.waitForTimeout(250);
        }
        return false;
    }

    private boolean checkSearchAccess(Page page, ProgressListener listener, BooleanSupplier stop) {
        boolean waited = HumanVerification.waitForUser(() -> List.of(page), "猎聘", listener, stop);
        if (stop.getAsBoolean()) return waited;
        String path = URI.create(page.url()).getPath();
        if ("/login".equals(path) || (path != null && path.startsWith("/login/"))
                || page.locator(".switch-type-mask-img-box:visible").count() > 0) {
            throw new HumanVerification.PausedRun("猎聘需要重新登录，浏览器页面已保留。登录后请重新开始");
        }
        dismissBlockingModals(page);
        return waited;
    }

    private String pageIdentity(Locator cards) {
        return (String) cards.evaluateAll("""
                cards => JSON.stringify(cards.map(card =>
                  card.querySelector('a[href*="/job/"]')?.getAttribute('href') ||
                  card.getAttribute('data-tlg-ext') || card.getAttribute('data-tlg-scm') ||
                  card.innerText.trim()))
                """);
    }

    /** 只有页面显示可用的下一页入口时才翻页。 */
    private boolean clickNextPage(Page page, ProgressListener listener) {
        Locator next = page.locator(PAGINATION_BOX
                + " li.ant-pagination-next:not(.ant-pagination-disabled):not([aria-disabled=true]):visible").first();
        if (next.count() == 0) return false;
        Locator link = next.locator("button.ant-pagination-item-link");
        Locator target = link.count() > 0 ? link.first() : next;
        if (!target.isEnabled()) return false;
        try {
            target.click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
            return true;
        } catch (TargetClosedError e) {
            throw e;
        } catch (Exception e) {
            listener.onProgress("猎聘下一页未打开，本关键词停止采集");
            log.debug("翻页失败: {}", e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 弹窗与拦截
    // ------------------------------------------------------------------

    /**
     * 关掉会吃掉点击的弹窗："完善简历"ant-modal 和每页都弹的订阅弹窗。
     * 猎聘这两个弹窗没有任何预告，跑批中途弹一次之后每次点击、每次翻页都会被
     * 遮罩吃掉（表现为点击超时），所以每张卡、每次翻页前都要清一遍。
     */
    private void dismissBlockingModals(Page page) {
        try {
            page.evaluate("""
                    () => {
                      const visible = node => node.getClientRects().length &&
                        getComputedStyle(node).visibility === 'visible';
                      for (const modal of document.querySelectorAll('.ant-modal-wrap')) {
                        if (!visible(modal)) continue;
                        const title = modal.querySelector('.ant-modal-title')?.innerText?.trim() || '';
                        if (/^(完善(?:个人)?简历|简历完善提醒|职位订阅|订阅职位)$/.test(title)) {
                          modal.querySelector('.ant-modal-close')?.click();
                        }
                      }
                      for (const button of document.querySelectorAll("div[class*='subscribe-close-btn']")) {
                        if (visible(button)) button.click();
                      }
                    }
                    """);
        } catch (TargetClosedError e) {
            throw e;
        } catch (Exception ignore) {
            // 页面切换中，下一轮会重新检查。
        }
    }

    private void applyEduLevel(Page page, String searchUrl, String myDegree,
                               AtomicReference<Map<String, String>> eduOptions,
                               AtomicReference<List<LiepinJobCard>> cache, String keyword,
                               ProgressListener listener, BooleanSupplier stop) {
        for (int i = 0; i < 16 && eduOptions.get().isEmpty() && !stop.getAsBoolean(); i++) {
            page.waitForTimeout(300);
        }
        if (stop.getAsBoolean()) return;
        checkSearchAccess(page, listener, stop);
        if (stop.getAsBoolean()) return;
        String code = LiepinEduLevel.pick(eduOptions.get(), myDegree);
        if (code == null) {
            listener.onProgress("【" + keyword + "】没拿到猎聘的学历选项，按全部学历搜索，"
                    + "高于「" + myDegree + "」的岗位在本地过滤");
            return;
        }
        String url = LiepinEduLevel.withEduLevel(searchUrl, code);
        listener.onProgress("【" + keyword + "】只看要求「" + myDegree + "」的岗位"
                + "（猎聘的学历筛选只能单选，标「学历不限」的岗位不在这批结果里）");
        cache.set(List.of());
        navigate(page, url, listener);
    }

    private static boolean isCondInitResponse(Response response) {
        try {
            String url = response.url();
            return url != null && url.contains(SEARCH_API + "-cond-init");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isSearchResponse(Response response) {
        try {
            String url = response.url();
            if (url == null || !url.contains(SEARCH_API)) {
                return false;
            }
            // cond-init 是筛选项初始化，和搜索接口同前缀，必须排除
            return !url.contains(SEARCH_API + "-cond-init");
        } catch (Exception e) {
            return false;
        }
    }

    private static String safeBody(Response response) {
        try {
            return response.text();
        } catch (Exception e) {
            // 响应体读不了（已销毁等）：本页按空数据处理
            return "{}";
        }
    }

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    private void navigate(Page page, String url, ProgressListener listener) {
        try {
            page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(NAV_TIMEOUT_MS)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            sleep(1500);
        } catch (Exception e) {
            // SPA 的 DOMCONTENTLOADED 也可能抛超时（长连接阻塞），页面往往已经可用
            log.warn("导航 {} 超时或异常（{}），按已加载页面继续", url, e.getMessage());
            if (listener != null) {
                listener.onProgress("页面加载较慢，继续等待内容渲染");
            }
            sleep(2000);
        }
    }

    private void configureTimeouts(Page page) {
        page.setDefaultTimeout(ACTION_TIMEOUT_MS);
        page.setDefaultNavigationTimeout(NAV_TIMEOUT_MS);
    }

    private static String safeText(Locator locator) {
        try {
            return locator.textContent();
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
        if (page == null) {
            return;
        }
        try {
            page.close();
        } catch (Exception ignore) {
        }
    }

    /** 调试截图：跑到关键节点存一张，出问题时能回放现场 */
    @SuppressWarnings("unused")
    public Path screenshot(Page page, String name) {
        try {
            Path dir = com.jobpilot.system.SystemPaths.dataDir().resolve("screenshots");
            java.nio.file.Files.createDirectories(dir);
            Path target = dir.resolve(name + "-" + Instant.now().toString().replace(":", "-") + ".png");
            page.screenshot(new Page.ScreenshotOptions().setPath(target).setFullPage(false));
            return target;
        } catch (Exception e) {
            log.warn("截图失败: {}", e.getMessage());
            return null;
        }
    }

    static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
