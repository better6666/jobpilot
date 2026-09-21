package com.jobpilot.boss;

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
import java.util.function.BooleanSupplier;

/**
 * Boss 直聘浏览器操作层。
 * <p>
 * <b>线程约束：所有方法都必须在 BrowserManager 的 dispatcher 线程里调用</b>
 * （即 submit/submitAsync 的任务体内）。Playwright 客户端非线程安全，
 * 且跨线程持有 page 句柄会随机抛 frame detached。
 * <p>
 * 已知 Boss 的坑（都来自实测，改这段代码前先读）：
 * <ul>
 *   <li>列表页在受控标签页会长时间转圈：每个 evaluate/locator 都必须显式超时，
 *       导航用 DOMCONTENTLOADED 而不是 LOAD/NETWORKIDLE（SPA + WebSocket 永远不 idle）</li>
 *   <li>列表第一个卡片默认就是选中态，点它不触发详情接口：先点第二个再切回第一个</li>
 *   <li>滚动加载靠"卡片数连续多轮不变"判定结束，中途 frame 失效是常态，要容错</li>
 *   <li>沟通按钮对未聊过的岗位显示"立即沟通"、聊过的显示"继续沟通"；
 *       点立即沟通会弹"已向BOSS发送消息"确认框，框里真正的"继续沟通"是
 *       A.btn-startchat（自身文本精确匹配），点完跳 /web/geek/chat，
 *       输入框是 div#chat-input.chat-input[contenteditable]。
 *       对话框文字会渗进页面大容器 div，包含匹配会点到遮罩上，必须按自身文本点</li>
 *   <li>发送判定：输入框被清空才算发出去；只按回车不一定发得出去</li>
 * </ul>
 */
@Slf4j
@Component
public class BossDriver {

    private final BrowserManager browserManager;

    public BossDriver(BrowserManager browserManager) {
        this.browserManager = browserManager;
    }

    private static final String BOSS_DOMAIN = "https://www.zhipin.com";
    private static final String LOGIN_COOKIE = "bst";
    private static final String CARD_XPATH =
            "//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]";
    private static final String DETAIL_API = "/wapi/zpgeek/job/detail.json";
    private static final int NAV_TIMEOUT_MS = 45_000;
    private static final int ACTION_TIMEOUT_MS = 10_000;
    /** 拦截卡片详情接口的等待：刚启动的浏览器首屏慢，比普通操作放宽 */
    private static final int CARD_CAPTURE_TIMEOUT_MS = 20_000;

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------

    /** 打开列表页并等待登录。已登录（profile 里有 cookie）直接返回。 */
    public LoginResult ensureLogin(int timeoutMinutes, ProgressListener listener, BooleanSupplier stop) {
        Page page = browserManager.context().newPage();
        configureTimeouts(page);
        try {
            navigate(page, BOSS_DOMAIN + "/web/geek/jobs", listener);
            if (hasLoginCookie()) {
                listener.onProgress("已检测到登录态，直接进入岗位列表");
                return LoginResult.LOGGED_IN;
            }
            listener.onProgress("未检测到登录态，请在浏览器窗口里扫码登录（等待 " + timeoutMinutes + " 分钟）");
            long deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L;
            while (System.currentTimeMillis() < deadline && !stop.getAsBoolean()) {
                sleep(2000);
                if (hasLoginCookie()) {
                    listener.onProgress("扫码登录成功");
                    return LoginResult.LOGGED_IN;
                }
            }
            return stop.getAsBoolean() ? LoginResult.TIMEOUT : LoginResult.TIMEOUT;
        } finally {
            closeQuietly(page);
        }
    }

    /** 当前 profile 是否已登录 Boss */
    public boolean hasLoginCookie() {
        try {
            return browserManager.context().cookies(BOSS_DOMAIN).stream()
                    .anyMatch(cookie -> LOGIN_COOKIE.equals(cookie.name));
        } catch (Exception e) {
            return false;
        }
    }

    /** 登录后的 cookie 快照（诊断用：确认 profile 里的登录态字段） */
    public List<String> loginCookieNames() {
        try {
            return browserManager.context().cookies(BOSS_DOMAIN).stream()
                    .map(cookie -> cookie.name).sorted().toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // 岗位采集
    // ------------------------------------------------------------------

    /**
     * 处理一个关键词：进搜索页 → 滚动加载完 → 逐个点击卡片读详情 →
     * 每读到一个岗位就回调 CardCallback（页面保持该卡片选中，回调里可以投递）。
     * <p>
     * 为什么采集和投递必须交错：立即沟通按钮只在完整详情页有，而旧工程实测
     * "从列表页新开标签页点立即沟通"走不通，必须先点列表卡片的"查看更多"链接——
     * 那个链接属于当前选中卡片的详情面板，所以投递时列表页必须还开着且停在该卡片上。
     */
    public void processKeyword(String searchUrl, String keyword, int maxCards,
                               ProgressListener listener, BooleanSupplier stop,
                               CardConsumer<BossJobCard> callback) {
        Page page = browserManager.context().newPage();
        configureTimeouts(page);
        try {
            navigate(page, searchUrl, listener);
            waitIfSecurityCheck(page, listener, stop);
            if (stop.getAsBoolean()) {
                return;
            }

            int loaded = scrollToLoadAll(page, listener, stop);
            listener.onProgress("【" + keyword + "】岗位加载完成，共 " + loaded + " 个，开始逐个处理");
            page.evaluate("window.scrollTo(0, 0)");
            sleep(1000);

            int count = Math.min(loaded, maxCards);
            for (int i = 0; i < count; i++) {
                if (stop.getAsBoolean()) {
                    listener.onProgress("收到停止指令，中断处理（已处理到第 " + i + " 个）");
                    break;
                }
                try {
                    // 每轮重新定位：虚拟滚动会让之前的 element handle 失效
                    Locator cardLocator = page.locator(CARD_XPATH);
                    if (i >= cardLocator.count()) {
                        break;
                    }
                    BossJobCard card = clickCardAndCapture(page, cardLocator, i);
                    if (card == null || card.getEncryptId() == null) {
                        log.debug("【{}】第 {} 个卡片没拦到详情数据，跳过", keyword, i + 1);
                        continue;
                    }
                    if ((i + 1) % 10 == 0) {
                        listener.onProgress("【" + keyword + "】进度 " + (i + 1) + "/" + count);
                    }
                    callback.accept(card, page);
                } catch (Exception e) {
                    log.debug("【{}】处理第 {} 个卡片异常: {}", keyword, i + 1, e.getMessage());
                }
            }
            listener.onProgress("【" + keyword + "】处理完成");
        } finally {
            closeQuietly(page);
        }
    }

    /**
     * 每个岗位的处理回调由编排层提供（{@link CardConsumer}），在 dispatcher
     * 线程内同步调用，页面保持该卡片选中，回调里可以投递。
     */

    /**
     * 点击第 i 个卡片并拦截 detail.json。
     * 第一个卡片页面加载后就是选中态，点它不发请求：先点第二个再切回第一个。
     */
    private BossJobCard clickCardAndCapture(Page page, Locator cardLocator, int index) {
        // 列表第一个卡片默认就是选中态，直接点它不触发详情接口：
        // 先点第二个把选中态挪走，再点回目标卡片，接口才会重新请求。
        // 注意与 total 无关——只跑 1 个岗位时同样要先热身，否则必然超时
        boolean needWarmup = index == 0 && cardLocator.count() > 1;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                if (needWarmup) {
                    try {
                        cardLocator.nth(1).click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
                        sleep(800);
                    } catch (Exception ignore) {
                        // 第二个点不动不影响主流程，继续尝试第一个
                    }
                }
                Response response = page.waitForResponse(
                        BossDriver::isDetailResponse,
                        new Page.WaitForResponseOptions().setTimeout(CARD_CAPTURE_TIMEOUT_MS),
                        () -> clickQuietly(cardLocator.nth(index)));
                sleep(500);
                if (response != null) {
                    try {
                        return BossJobCard.parse(response.text());
                    } catch (Exception e) {
                        log.warn("读取详情响应体失败: {}", e.getMessage());
                        return null;
                    }
                }
            } catch (Exception e) {
                // 刚启动的浏览器首屏渲染慢，点击可能整轮超时；重试一次再放弃这个卡片
                log.debug("第 {} 个卡片第 {}/2 次拦截详情失败: {}", index + 1, attempt, e.getMessage());
            }
            sleep(1000);
        }
        return null;
    }

    private static boolean isDetailResponse(Response response) {
        try {
            return response.url() != null && response.url().contains(DETAIL_API)
                    && "GET".equalsIgnoreCase(response.request().method());
        } catch (Exception e) {
            return false;
        }
    }

    private static void clickQuietly(Locator locator) {
        try {
            locator.click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
        } catch (Exception ignore) {
            // waitForResponse 会因超时返回 null，由调用方处理
        }
    }

    /** 滚动到列表底部：卡片数连续 3 轮不变认为加载完（Boss 没有可靠的分页信号） */
    private int scrollToLoadAll(Page page, ProgressListener listener, BooleanSupplier stop) {
        int lastCount = -1;
        int stableRounds = 0;
        for (int round = 0; round < 300; round++) {
            if (stop.getAsBoolean()) {
                break;
            }
            try {
                page.evaluate("window.scrollBy(0, Math.round(window.innerHeight * 1.5))");
            } catch (Exception e) {
                log.debug("滚动执行失败（frame 可能已失效）: {}", e.getMessage());
                break;
            }
            sleep(1200);
            int count = safeCardCount(page);
            if (count <= 0) {
                break;
            }
            if (count == lastCount) {
                if (++stableRounds >= 3) {
                    break;
                }
            } else {
                stableRounds = 0;
                lastCount = count;
            }
        }
        return safeCardCount(page);
    }

    private int safeCardCount(Page page) {
        try {
            return page.locator(CARD_XPATH).count();
        } catch (Exception e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // 投递
    // ------------------------------------------------------------------

    /**
     * 投递一个岗位：从列表页打开详情页 → 立即沟通 → 弹窗处理 → 输入话术 → 发送。
     * 返回结果由调用方落库；单个岗位失败不抛异常，返回 FAILED 让调用方继续。
     */
    public DeliveryOutcome deliver(Page listPage, BossJobCard card, String greeting,
                                   boolean dryRun, ProgressListener listener, BooleanSupplier stop) {
        String target = (card.getBrandName() != null ? card.getBrandName() : "?")
                + " | " + (card.getJobName() != null ? card.getJobName() : "?");
        if (dryRun) {
            listener.onProgress("预演：跳过真实发送 | " + target);
            return new DeliveryOutcome(DeliveryStatus.PREVIEW, null, greeting);
        }
        if (stop.getAsBoolean()) {
            return new DeliveryOutcome(DeliveryStatus.FAILED, "已停止", greeting);
        }
        Page detailPage = null;
        try {
            // 1. 从列表页详情面板拿"查看更多"链接（Boss 的立即沟通按钮只在完整详情页有）
            Locator moreBtn = listPage.locator("a.more-job-btn");
            if (moreBtn.count() == 0) {
                return new DeliveryOutcome(DeliveryStatus.FAILED, "未找到更多详情按钮", greeting);
            }
            String href = moreBtn.first().getAttribute("href");
            if (href == null || !href.startsWith("/job_detail/")) {
                return new DeliveryOutcome(DeliveryStatus.FAILED, "详情链接异常: " + href, greeting);
            }

            // 2. 新开标签页打开详情页（不动列表页，回来还能继续点下一个卡片）
            detailPage = browserManager.context().newPage();
            configureTimeouts(detailPage);
            navigate(detailPage, BOSS_DOMAIN + href, listener);
            waitIfSecurityCheck(detailPage, listener, stop);

            // 3. 列表接口没拦住 JD 时从详情页 DOM 兜底
            if (isBlank(card.getPostDescription())) {
                String jd = firstInnerText(detailPage,
                        "div.job-sec-text", ".job-detail-section", ".job-detail-box", ".desc");
                if (!isBlank(jd)) {
                    card.setPostDescription(jd.trim());
                }
            }

            // 4. 沟通按钮：未聊过的岗位显示"立即沟通"，聊过的显示"继续沟通"，两者都能点
            Locator chatBtn = detailPage.locator("a.btn-startchat, a.op-btn-chat");
            boolean found = false;
            for (int i = 0; i < 5 && !stop.getAsBoolean(); i++) {
                for (int j = 0; j < chatBtn.count(); j++) {
                    Locator candidate = chatBtn.nth(j);
                    try {
                        if (!candidate.isVisible()) {
                            continue;
                        }
                        String text = candidate.textContent();
                        if (text != null && (text.contains("立即沟通") || text.contains("继续沟通"))) {
                            found = true;
                            break;
                        }
                    } catch (Exception ignore) {
                        // 元素可能刚被页面重绘替换，接着找下一个
                    }
                }
                if (found) {
                    break;
                }
                sleep(1000);
            }
            if (!found) {
                return new DeliveryOutcome(DeliveryStatus.FAILED, "未找到沟通按钮", greeting);
            }
            chatBtn.first().click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
            sleep(1500);

            // 5. "已向BOSS发送消息"确认框与聊天输入框合并轮询：
            // 输入框没出现就点一次"继续沟通"（对话框可能比固定等待出现得晚），
            // 点了继续沟通页面会跳到 /web/geek/chat，输入框就在那里
            Page chatPage = detailPage;
            Locator input = null;
            for (int i = 0; i < 20 && !stop.getAsBoolean(); i++) {
                input = findChatInput(detailPage);
                if (input != null) {
                    chatPage = detailPage;
                    break;
                }
                // 立即沟通可能把聊天开到了新标签页
                for (Page candidate : browserManager.context().pages()) {
                    if (candidate != detailPage && candidate != listPage) {
                        Locator candidateInput = findChatInput(candidate);
                        if (candidateInput != null) {
                            chatPage = candidate;
                            input = candidateInput;
                            break;
                        }
                    }
                }
                if (input != null) {
                    break;
                }
                clickContinueChat(detailPage);
                sleep(1000);
            }
            if (input == null) {
                return new DeliveryOutcome(DeliveryStatus.FAILED, "未找到聊天输入框", greeting);
            }

            // 7. 输入话术并发送
            input.first().click();
            sleep(300);
            input.first().pressSequentially(greeting,
                    new Locator.PressSequentiallyOptions().setDelay(60));
            sleep(800);

            boolean sent = false;
            input.first().press("Enter");
            sleep(1500);
            if (isInputCleared(input.first())) {
                sent = true;
            } else {
                input.first().press("Control+Enter");
                sleep(1500);
                if (isInputCleared(input.first())) {
                    sent = true;
                }
            }
            if (!sent) {
                // 快捷键都不灵就找发送按钮
                Locator sendBtn = chatPage.locator(
                        "button:has-text('发送'), div.btn-send, div.send-message");
                if (sendBtn.count() > 0) {
                    sendBtn.first().click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
                    sleep(1500);
                    sent = isInputCleared(input.first());
                }
            }
            if (!sent) {
                return new DeliveryOutcome(DeliveryStatus.FAILED, "消息未确认发出（输入框仍有内容）", greeting);
            }
            listener.onProgress("已发送打招呼语 | " + target);
            return new DeliveryOutcome(DeliveryStatus.DELIVERED, null, greeting);
        } catch (Exception e) {
            log.warn("投递过程异常 | {}: {}", target, e.getMessage());
            return new DeliveryOutcome(DeliveryStatus.FAILED, e.getMessage(), greeting);
        } finally {
            closeQuietly(detailPage);
        }
    }

    /** 页面提示今日沟通上限：必须停，继续点只会全量失败 */
    public boolean limitReachedOnPage(Page page) {
        try {
            String text = page.locator("body").innerText(
                    new Locator.InnerTextOptions().setTimeout(ACTION_TIMEOUT_MS));
            return text != null && text.contains("上限") && text.contains("今日");
        } catch (Exception e) {
            return false;
        }
    }

    private Locator findChatInput(Page page) {
        try {
            Locator locator = page.locator(
                    "div#chat-input.chat-input[contenteditable='true'], textarea.input-area");
            if (locator.count() > 0 && locator.first().isVisible()) {
                return locator;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /**
     * 点掉"已向BOSS发送消息"确认框里的"继续沟通"。
     *
     * 必须按"自身文本"精确匹配：对话框文字会渗进页面上的大容器 div
     * （job-banner、info-primary 等的 innerText 都含"继续沟通"），
     * 用 :has-text 之类包含匹配会先点到容器，点遮罩把对话框关掉但聊天页没打开。
     */
    private void clickContinueChat(Page page) {
        try {
            page.evaluate("() => {"
                    + "  const ownText = e => [...e.childNodes]"
                    + "      .filter(n => n.nodeType === 3).map(n => n.textContent.trim()).join('');"
                    + "  const els = [...document.querySelectorAll('button, a, span')];"
                    + "  const btn = els.find(e => e.offsetParent !== null && ownText(e) === '继续沟通');"
                    + "  if (btn) { btn.click(); return true; } return false; }");
        } catch (Exception ignore) {
            // 页面在跳转过程中 evaluate 失败属正常，下一轮轮询会重试
        }
    }

    private boolean isInputCleared(Locator input) {
        try {
            String tag = input.evaluate("el => el.tagName.toLowerCase()").toString();
            if ("textarea".equals(tag) || "input".equals(tag)) {
                String value = input.inputValue();
                return value == null || value.isBlank();
            }
            Object text = input.evaluate("el => el.innerText");
            return text == null || text.toString().isBlank();
        } catch (Exception e) {
            // 输入框可能已随页面关闭，保守判为未发送，让上层记录失败人工复核
            return false;
        }
    }

    private String firstInnerText(Page page, String... selectors) {
        for (String selector : selectors) {
            try {
                Locator locator = page.locator(selector);
                if (locator.count() > 0) {
                    String text = locator.first().innerText(
                            new Locator.InnerTextOptions().setTimeout(ACTION_TIMEOUT_MS));
                    if (!isBlank(text)) {
                        return text;
                    }
                }
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    /** Boss 被风控跳到验证页时：提示用户手动过验证，然后原地等 */
    private void waitIfSecurityCheck(Page page, ProgressListener listener, BooleanSupplier stop) {
        try {
            String url = page.url();
            if (url != null && (url.contains("verify") || url.contains("captcha"))) {
                listener.onProgress("触发 Boss 安全校验，请在浏览器窗口里手动完成验证");
                int waited = 0;
                while (waited < 300 && !stop.getAsBoolean()) {
                    sleep(2000);
                    waited += 2;
                    String current = page.url();
                    if (current != null && !current.contains("verify") && !current.contains("captcha")) {
                        listener.onProgress("安全校验已通过");
                        return;
                    }
                }
            }
        } catch (Exception ignore) {
        }
    }

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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
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
