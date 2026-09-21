package com.jobpilot.liepin;

import com.jobpilot.browser.BrowserManager;
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

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * 猎聘浏览器操作层。
 * <p>
 * <b>线程约束：所有方法都必须在 BrowserManager 的 dispatcher 线程里调用</b>
 * （即 submit/submitAsync 的任务体内）。Playwright 客户端非线程安全。
 * <p>
 * 已知猎聘的坑（都来自实测，改这段代码前先读）：
 * <ul>
 *   <li>搜索页是 AntD 服务端分页，不是无限滚动：翻页只能点
 *       {@code li.ant-pagination-next} 里的 {@code button.ant-pagination-item-link}，
 *       改 URL 的 currentPage 没用</li>
 *   <li>总页数要取分页框下<b>倒数第二个</b> li——最后一个是 next 按钮，
 *       取最后一个会解析失败</li>
 *   <li>结构化数据来自拦截 {@code api-c.liepin.com/.../pc-search-job}，
 *       <b>必须排除同前缀的 pc-search-job-cond-init</b>（筛选项初始化，拦了会
 *       白白等一次响应）</li>
 *   <li>接口返回的实体列表<b>按下标与 DOM 卡片一一对应</b>：第 i 张卡用第 i 个
 *       实体。响应是整页替换的，所以每次要等"新一次响应"再读缓存，不能读旧页的</li>
 *   <li>沟通按钮要 hover HR 区才渲染，不 hover 就整张卡被静默跳过</li>
 *   <li>卡片里同时有收藏/分享等 round 按钮，<b>只能按自身文本"聊一聊"判定</b>；
 *       "继续聊"是已投递状态，点了只会重开旧会话</li>
 *   <li>点"聊一聊"后平台自动发出默认招呼语，聊天以页内 IM overlay 打开，
 *       <b>没有确认对话框、不开新标签页</b>——Boss 那套确认框逻辑这里不需要</li>
 *   <li>"完善简历"弹窗和订阅弹窗会遮罩吃掉所有点击，每张卡、每次翻页前都要关</li>
 *   <li>列表页在受控标签页会长时间转圈：每个 evaluate/locator 都显式超时，
 *       导航用 DOMCONTENTLOADED 而不是 LOAD/NETWORKIDLE</li>
 * </ul>
 */
@Slf4j
@Component
public class LiepinDriver {

    private final BrowserManager browserManager;

    public LiepinDriver(BrowserManager browserManager) {
        this.browserManager = browserManager;
    }

    private static final String DOMAIN = "https://www.liepin.com";
    private static final String LOGIN_URL = "https://www.liepin.com/login";
    /** 登录令牌。快照里 liepin_login_valid 的值可能是 "0"，不能作判据 */
    private static final String LOGIN_COOKIE = "lt_auth";
    private static final String CARD_SELECTOR = "div[class*='job-card-pc-container']";
    private static final String PAGINATION_BOX = ".list-pagination-box";
    private static final String CHAT_HEADER = ".__im_basic__header-wrap";
    private static final String CHAT_CLOSE = "div.__im_basic__contacts-title svg";
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
        Page page = browserManager.context().newPage();
        configureTimeouts(page);
        // 本页接口实体的缓存。每次命中搜索接口就整页替换，所以读之前必须确认
        // 是一次"新"响应，否则拿到的是上一页的数据（下标对不上 DOM）
        AtomicReference<List<LiepinJobCard>> cache = new AtomicReference<>(List.of());
        AtomicLong responseSeq = new AtomicLong();
        page.onResponse(response -> {
            if (isSearchResponse(response)) {
                List<LiepinJobCard> cards = LiepinJobCard.parseList(safeBody(response));
                cache.set(cards);
                responseSeq.incrementAndGet();
                log.debug("【{}】拦截到 {} 个岗位实体", keyword, cards.size());
            }
        });
        try {
            navigate(page, searchUrl, listener);
            dismissBlockingModals(page);
            if (!waitForPagination(page, listener)) {
                listener.onProgress("【" + keyword + "】搜索结果分页未出现（可能被登录或验证弹窗挡住），跳过");
                return;
            }
            int maxPage = readMaxPage(page);
            listener.onProgress("【" + keyword + "】搜索页就绪，共 " + maxPage + " 页可翻");

            int processed = 0;
            for (int pageIndex = 1; pageIndex <= Math.min(maxPage, MAX_PAGE); pageIndex++) {
                if (stop.getAsBoolean()) {
                    listener.onProgress("收到停止指令，中断采集（已处理 " + processed + " 个）");
                    break;
                }
                if (pageIndex > 1) {
                    // 翻页后必须等一次新的接口响应，缓存里才是这一页的实体
                    if (!clickNextPage(page, listener)) {
                        listener.onProgress("【" + keyword + "】已到最后一页");
                        break;
                    }
                    waitForNewResponse(responseSeq, RESPONSE_TIMEOUT_MS, stop);
                }
                dismissBlockingModals(page);
                if (!waitForCards(page, listener)) {
                    listener.onProgress("【" + keyword + "】第 " + pageIndex + " 页没有岗位卡片，跳过");
                    continue;
                }
                int count = page.locator(CARD_SELECTOR).count();
                // 快照：这一轮的卡片和实体必须来自同一次响应
                List<LiepinJobCard> entities = cache.get();
                listener.onProgress("【" + keyword + "】第 " + pageIndex + " 页 " + count + " 个岗位");
                for (int i = 0; i < count; i++) {
                    if (stop.getAsBoolean() || processed >= maxCards) {
                        break;
                    }
                    try {
                        Locator card = page.locator(CARD_SELECTOR).nth(i);
                        if (card.count() == 0) {
                            break;
                        }
                        dismissBlockingModals(page);
                        prepareCard(page, card, i);
                        currentCardIndex = i;
                        LiepinJobCard jobCard = i < entities.size() ? entities.get(i) : null;
                        if (jobCard == null) {
                            // 接口没拦到时至少有 jobId 能去重，其余字段留空
                            jobCard = fallbackCard(card);
                            if (jobCard == null) {
                                log.debug("【{}】第 {} 张卡既没拦到实体也提不到 jobId，跳过", keyword, i + 1);
                                continue;
                            }
                        }
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
            }
            listener.onProgress("【" + keyword + "】处理完成，共 " + processed + " 个");
        } finally {
            currentCardIndex = -1;
            closeQuietly(page);
        }
    }

    /** 接口没拦到时从卡片 DOM 提 jobId（data-tlg-ext / data-tlg-scm） */
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
     * 投递一个岗位：hover 出沟通按钮 → 点"聊一聊" → 等聊天窗 → （可选）补一句
     * 追问 → 关聊天窗。
     * <p>
     * 成功判据是聊天窗头部出现——平台在点击时就已经把默认招呼语发给 HR 了，
     * 所以追问发不出去、聊天窗关不掉，都算投递成功。
     */
    public DeliveryOutcome deliver(LiepinJobCard card, Page listPage, LiepinProperties.LiepinConfig config,
                                   ProgressListener listener, BooleanSupplier stop) {
        String target = (card.getBrandName() != null ? card.getBrandName() : "?")
                + " | " + (card.getJobName() != null ? card.getJobName() : "?");
        int index = currentCardIndex;
        if (index < 0) {
            return DeliveryOutcome.failed("卡片下标丢失，无法定位沟通按钮");
        }
        if (config.isDryRun()) {
            listener.onProgress("预演：跳过真实投递 | " + target);
            return DeliveryOutcome.preview(config.getSayHi());
        }
        if (stop.getAsBoolean()) {
            return DeliveryOutcome.failed("已停止");
        }
        try {
            Locator cardLocator = listPage.locator(CARD_SELECTOR).nth(index);
            if (cardLocator.count() == 0) {
                return DeliveryOutcome.failed("卡片已不在列表里（页面可能刷新过）");
            }
            prepareCard(listPage, cardLocator, index);
            Locator button = findChatButton(cardLocator);
            if (button == null) {
                return DeliveryOutcome.failed("未找到沟通按钮");
            }
            String text = safeText(button);
            if (text != null && text.contains("继续聊")) {
                // 已经聊过了：平台侧就是已投递，不重复点
                return new DeliveryOutcome(DeliveryStatus.DELIVERED, null, null);
            }
            dismissBlockingModals(listPage);
            clickWithHoverWake(button);

            if (!waitForChatWindow(listPage, stop)) {
                // 聊天窗没出来也按成功算：点击本身已经触发平台发送默认招呼语
                log.debug("聊天窗未确认出现 | {}", target);
            }
            if (config.getSayHi() != null && !config.getSayHi().isBlank()) {
                sendFollowUp(listPage, config.getSayHi());
            }
            closeChatWindow(listPage);
            listener.onProgress("已打招呼 | " + target);
            return DeliveryOutcome.delivered(config.getSayHi());
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

    /** 等聊天窗头部出现。用轮询而不是 waitForSelector：本平台后者不可靠 */
    private boolean waitForChatWindow(Page page, BooleanSupplier stop) {
        for (int i = 0; i < 6 && !stop.getAsBoolean(); i++) {
            try {
                if (page.locator(CHAT_HEADER).count() > 0) {
                    return true;
                }
            } catch (Exception ignore) {
            }
            sleep(500);
        }
        return false;
    }

    /**
     * 往聊天里补一句追问。全程 best-effort：任何一步失败都静默返回，
     * 不影响"已打招呼"的判定——默认招呼语在点击时就已发出。
     */
    private void sendFollowUp(Page page, String message) {
        try {
            Locator input = null;
            for (String selector : new String[]{
                    "div.__im_basic__chat-wrapper [contenteditable='true']:not([style*='display: none'])",
                    ".__im_basic__chat-input textarea",
                    "div[class*='im-basic'] textarea",
                    "div[class*='chat'] div[contenteditable='true']"}) {
                Locator candidate = page.locator(selector);
                if (candidate.count() > 0 && candidate.first().isVisible()) {
                    input = candidate.first();
                    break;
                }
            }
            if (input == null) {
                return;
            }
            Object tag = input.evaluate("el => el.tagName.toLowerCase()");
            if ("textarea".equals(String.valueOf(tag))) {
                input.fill(message);
            } else {
                // contenteditable 用 fill 无效，必须写 innerText 再派发 input 事件
                input.evaluate("(el, msg) => {"
                        + "  el.innerText = msg;"
                        + "  el.dispatchEvent(new Event('input', {bubbles: true}));"
                        + "}", message);
            }
            sleep(1000);
            input.press("Enter");
            sleep(1000);
        } catch (Exception e) {
            log.debug("追问语发送失败（不影响投递判定）: {}", e.getMessage());
        }
    }

    private void closeChatWindow(Page page) {
        try {
            Locator close = page.locator(CHAT_CLOSE);
            if (close.count() > 0) {
                close.first().click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
                sleep(500);
            }
        } catch (Exception e) {
            log.debug("关闭聊天窗失败（不影响投递判定）: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 分页
    // ------------------------------------------------------------------

    /** 等分页框出现。返回 false = 一直没出现（被登录/验证弹窗挡住） */
    private boolean waitForPagination(Page page, ProgressListener listener) {
        for (int i = 0; i < 20; i++) {
            try {
                Locator box = page.locator(PAGINATION_BOX);
                if (box.count() > 0 && box.first().isVisible()) {
                    return true;
                }
            } catch (Exception ignore) {
            }
            sleep(1000);
        }
        return false;
    }

    /**
     * 读总页数：分页框下<b>倒数第二个</b> li。
     * 最后一个是 next 按钮，取它会解析失败；解析不出来按 1 页处理（只跑当前页）。
     */
    private int readMaxPage(Page page) {
        try {
            Object result = page.evaluate("() => {" +
                    "  const box = document.querySelector('.list-pagination-box');" +
                    "  if (!box) return 0;" +
                    "  const lis = box.querySelectorAll('li');" +
                    "  if (lis.length < 2) return 0;" +
                    "  const n = parseInt(lis[lis.length - 2].textContent.trim(), 10);" +
                    "  return isNaN(n) ? 0 : n;" +
                    "}");
            int pages = result instanceof Number number ? number.intValue() : 0;
            return pages > 1 ? pages : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    /** 点下一页。返回 false = 已经是最后一页或点不动 */
    private boolean clickNextPage(Page page, ProgressListener listener) {
        try {
            Locator next = page.locator(PAGINATION_BOX + " li.ant-pagination-next");
            if (next.count() == 0) {
                return false;
            }
            String cls = next.first().getAttribute("class");
            if (cls != null && cls.contains("ant-pagination-disabled")) {
                return false;
            }
            Locator link = next.locator("button.ant-pagination-item-link");
            Locator target = link.count() > 0 ? link.first() : next.first();
            try {
                target.click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
            } catch (Exception e) {
                // 被弹窗遮住就关掉再原样重试一次
                dismissBlockingModals(page);
                target.click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
            }
            return true;
        } catch (Exception e) {
            log.debug("翻页失败: {}", e.getMessage());
            return false;
        }
    }

    /** 等岗位卡片出现。返回 false = 一直没出现 */
    private boolean waitForCards(Page page, ProgressListener listener) {
        for (int i = 0; i < 15; i++) {
            try {
                if (page.locator(CARD_SELECTOR).count() > 0) {
                    return true;
                }
            } catch (Exception ignore) {
            }
            sleep(1000);
        }
        return false;
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
            page.evaluate("() => {" +
                    "  const visible = el => el && el.offsetParent !== null;" +
                    "  let closed = false;" +
                    "  document.querySelectorAll('.ant-modal-wrap').forEach(w => {" +
                    "    if (!visible(w)) return;" +
                    "    const btn = w.querySelector('.ant-modal-close');" +
                    "    if (btn) { btn.click(); closed = true; }" +
                    "  });" +
                    "  document.querySelectorAll(\"div[class*='subscribe-close-btn']\").forEach(b => {" +
                    "    if (visible(b)) { b.click(); closed = true; }" +
                    "  });" +
                    "  if (!closed) {" +
                    "    const still = [...document.querySelectorAll('.ant-modal-wrap')].some(visible);" +
                    "    if (still) return 'escape';" +
                    "  }" +
                    "  return closed ? 'closed' : 'none';" +
                    "}");
        } catch (Exception ignore) {
        }
        try {
            // 没有关闭按钮的弹窗只能按 Esc
            if (page.locator(".ant-modal-wrap").count() > 0) {
                page.keyboard().press("Escape");
                sleep(300);
            }
        } catch (Exception ignore) {
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

    /** 等到一次新的接口响应。等不到也返回——用旧缓存总比卡死强 */
    private void waitForNewResponse(AtomicLong seq, int timeoutMs, BooleanSupplier stop) {
        long before = seq.get();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !stop.getAsBoolean()) {
            if (seq.get() > before) {
                return;
            }
            sleep(300);
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
