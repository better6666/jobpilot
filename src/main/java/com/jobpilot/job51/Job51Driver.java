package com.jobpilot.job51;

import com.jobpilot.browser.BrowserManager;
import com.jobpilot.delivery.CardConsumer;
import com.jobpilot.delivery.DeliveryOutcome;
import com.jobpilot.delivery.DeliveryStatus;
import com.jobpilot.delivery.LoginResult;
import com.jobpilot.delivery.ProgressListener;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 51job 浏览器操作层。
 * <p>
 * <b>线程约束：所有方法都必须在 BrowserManager 的 dispatcher 线程里调用</b>
 * （即 submit/submitAsync 的任务体内）。Playwright 客户端非线程安全。
 * <p>
 * 已知 51job 的坑（都来自实测，改这段代码前先读）：
 * <ul>
 *   <li><b>应届生外链劫持（第一大坑）</b>：搜索列表里混着 yingjiesheng.com
 *       的外链职位，点一下会 window.open 弹外部站甚至把主页劫走。四层防御：
 *       route abort、window.open 覆写、onPopup/onPage 即时关闭、点击前 evaluate 预检</li>
 *   <li><b>jobId 不在链接里</b>：新版列表的 {@code a[href]} 全是公司页
 *       （{@code /all/coXXXX.html}），数字 jobId 只存在于卡片 div 的
 *       {@code sensorsdata} 埋点 JSON 里，和搜索接口返回的是同一个值</li>
 *   <li>搜索接口 {@code /api/job/search-pc} 的响应<b>按 URL 上的 requestId 去重</b>：
 *       同一次搜索会发多次请求（初始化、排序、翻页），不去重会把旧页数据当新页用</li>
 *   <li>投递按钮只在列表容器 {@code .j_joblist} / {@code .j_result} 里，
 *       <b>必须排除"一键投递"</b>，否则命中顶导航的"投递记录"之类</li>
 *   <li>已投递状态只体现在按钮文本里（"已投递"/"已申请"/"记录"），接口不返回</li>
 *   <li>弹窗是 Vant + ElementUI 两套混用，扫码下载 App、投递成功框都要关，
 *       遮罩会吃掉后续所有点击</li>
 *   <li>阿里 WAF（acw_tc cookie）命中验证时当前关键词直接放弃，重试只会更惨</li>
 *   <li><b>登录态只能用 DOM 判</b>：we.51job.com 登录后发的 cookie 是不带平台名的
 *       {@code JSESSIONID}（匿名访客也有），拿 cookie 名当判据会把已登录误判成未登录，
 *       整个平台一个岗位都投不出去。见 {@link #isLoggedIn}</li>
 *   <li>日上限 toast 只存在一两秒，投递完要立刻探</li>
 *   <li>翻页是 Element Plus 分页：next 按钮 → 页码数字 → 跳页输入框，三级降级</li>
 * </ul>
 */
@Slf4j
@Component
public class Job51Driver {

    private final BrowserManager browserManager;

    public Job51Driver(BrowserManager browserManager) {
        this.browserManager = browserManager;
    }

    private static final String DOMAIN = "https://we.51job.com";
    private static final String SEARCH_URL = "https://we.51job.com/pc/search";
    /**
     * 登录态信号：用户名入口、"我的投递"入口、通用用户信息块。
     * 三个都是 51job 导航栏登录后的实际节点（其中前两个来自上游 get_jobs 的真机验证）。
     */
    private static final String[] LOGGED_IN_SELECTORS = {
            "a.uname.e_icon.at",
            "a[href*='/pc/my/myjob']",
            ".login-info, .user-info, .username"
    };
    /** 未登录的硬信号：导航栏上的登录/注册按钮 */
    private static final String[] LOGGED_OUT_SELECTORS = {
            "span.login.loginBtnClick",
            "text=登录/注册"
    };
    private static final String LIST_CONTAINER = ".j_joblist, .j_result";
    /** 页面渲染settling的短轮询次数（×500ms ≈ 14 秒） */
    private static final int SETTLE_POLLS = 28;
    /** 搜索接口。只拦 GET，POST 的是别的功能 */
    private static final String SEARCH_API = "/api/job/search-pc";
    /** 投递按钮：限定列表容器 + 排除"一键投递"，两道都要。
     *  容器必须用 :is() 包起来——写成 ".j_joblist, .j_result button..." 时逗号会把
     *  选择器拆成两支，".j_joblist" 那一支匹配的是容器本身而不是按钮，
     *  nth(index) 会整体错位 */
    private static final String APPLY_BUTTON =
            ":is(.j_joblist, .j_result) button:has-text('投递'):not(:has-text('一键投递'))";
    private static final String APPLY_BUTTON_FALLBACK =
            LIST_CONTAINER + " .btn:has-text('投递')";

    private static final int NAV_TIMEOUT_MS = 45_000;
    private static final int ACTION_TIMEOUT_MS = 10_000;
    private static final int RESPONSE_TIMEOUT_MS = 20_000;
    private static final int MAX_PAGE = 50;

    /** 命中这些字样的外链一律不碰：应届生/校招跳转会劫持主页 */
    private static final String[] HIJACK_MARKERS = {"yingjiesheng", "xyz.51job", "partner=51wspcjoblist"};

    /** 日上限提示。toast 只闪一两秒，所以三处都要探 */
    private static final String[] LIMIT_KEYWORDS = {
            "今日投递太多", "您今日投递太多", "休息一下明天再来", "达到上限", "次数过多"
    };

    /** 空结果提示：不是错误，但要告诉用户"这个词没搜到"而不是默默跑完 */
    private static final String[] NO_JOB_KEYWORDS = {
            "暂无职位", "没有符合条件的职位", "暂无符合条件职位", "暂无相关职位"
    };

    /**
     * 当前正在处理的投递按钮下标。
     * <p>
     * 采集阶段和投递阶段是分开的两次调用（编排层要在中间做去重和过滤），
     * 而 51job 的卡片没有稳定的 data 属性可定位，只能靠下标找回那个按钮。
     * 整个流程跑在单线程 dispatcher 上，collect 里设好值、deliver 里立刻用，
     * 不存在竞争。
     */
    private volatile int currentButtonIndex = -1;

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------

    /**
     * 打开搜索页并等待登录。判据是页面 DOM，见 {@link #isLoggedIn}。
     * <p>
     * 登录页要在同一个标签页里手动扫码，所以这里只导航 + 轮询，不做任何点击。
     */
    public LoginResult ensureLogin(int timeoutMinutes, ProgressListener listener, BooleanSupplier stop) {
        Page page = browserManager.context().newPage();
        configureTimeouts(page);
        try {
            navigate(page, SEARCH_URL, listener);
            // 搜索页是 SPA，导航完成（DOMCONTENTLOADED）时导航栏还没渲染，
            // 立刻判一次必然报"未登录"。先给十几秒短轮询 settle，还不行才提示用户扫码，
            // 否则窗口刚开就闪一句"请登录"，用户扫完码又被后面的判据绕回去。
            for (int i = 0; i < SETTLE_POLLS && !stop.getAsBoolean(); i++) {
                if (isLoggedIn(page)) {
                    listener.onProgress("已检测到 51job 登录态，直接进入岗位列表");
                    return LoginResult.LOGGED_IN;
                }
                sleep(500);
            }
            listener.onProgress("未检测到 51job 登录态，请在浏览器窗口里登录（等待 " + timeoutMinutes + " 分钟）");
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

    /**
     * 当前页面是否已登录 51job——只看 DOM，不看 cookie。
     * <p>
     * <b>为什么不能靠 cookie 名判</b>：这个判据曾经是"profile 里有没有名为
     * {@code 51job} 的 cookie"，而 we.51job.com 登录后发的其实是不带平台名的
     * {@code JSESSIONID}（匿名访客第一次访问也发），结果是<b>登录了也永远判成未登录</b>，
     * 点开始就卡在"请扫码登录"直到超时，一个岗位都不投。
     * <p>
     * 三个登录态信号（用户名入口 / 我的投递 / 通用用户信息块）任中即算已登录；
     * 导航栏的"登录/注册"按钮是未登录的硬信号。两个都没命中时按未登录处理
     * （宁可让用户重扫一次，也不要带着未登录的会话去点投递，那只会全部失败），
     * 同时打一行诊断日志，方便平台改版后对着选择器找原因。
     */
    public boolean isLoggedIn(Page page) {
        for (String selector : LOGGED_IN_SELECTORS) {
            Locator hit = findVisible(page, selector);
            if (hit != null) {
                // 命中哪个节点要打出来：51job 的 .username 这类泛用类名有假阳性风险，
                // 光看"已登录/未登录"的结论排查不了
                log.info("51job 登录判据命中 | {} | 节点文本={}", selector, nodeText(hit));
                return true;
            }
        }
        boolean loggedOutShown = findVisible(page, LOGGED_OUT_SELECTORS[0]) != null
                || findVisible(page, LOGGED_OUT_SELECTORS[1]) != null;
        if (!loggedOutShown) {
            log.info("51job 登录态两个方向都没探到选择器，按未登录处理 | cookie={}", loginCookieNames());
        }
        return false;
    }

    /** 选择器命中的第一个可见节点；没有则 null */
    private static Locator findVisible(Page page, String selector) {
        try {
            Locator hit = page.locator(selector).first();
            return hit.count() > 0 && hit.isVisible() ? hit : null;
        } catch (Exception e) {
            // 选择器本身不被当前 Playwright 语法接受、或页面刚好在跳转，都算没命中
            return null;
        }
    }

    private static String nodeText(Locator locator) {
        try {
            String text = locator.textContent();
            if (text == null) {
                return "(无文本)";
            }
            text = text.trim().replaceAll("\\s+", " ");
            return text.length() > 30 ? text.substring(0, 30) + "…" : text;
        } catch (Exception e) {
            return "(读不到)";
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
     * 处理一个关键词：进搜索页 → 点排序热身 → 逐页翻 → 逐个岗位回调
     * （回调里可以投递，页面还停在这个岗位的按钮上）。
     *
     * 岗位实体来自拦截 {@code /api/job/search-pc} 的 JSON，按 jobId 建索引；
     * DOM 那侧只负责提供 jobId 和按钮位置。两边按 jobId 对齐而不是按下标对齐——
     * 列表里可能插着不在接口结果里的置顶/广告条目，按下标会整体错位。
     */
    public void processKeyword(String searchUrl, String keyword, int maxCards,
                               ProgressListener listener, BooleanSupplier stop,
                               CardConsumer<Job51JobCard> consumer) {
        Page page = browserManager.context().newPage();
        configureTimeouts(page);
        installHijackDefenses(page);
        // 按 requestId 去重后的接口实体，jobId → 卡片
        AtomicReference<Map<String, Job51JobCard>> cache = new AtomicReference<>(new HashMap<>());
        Set<String> seenRequestIds = new HashSet<>();
        AtomicLong responseSeq = new AtomicLong();
        page.onResponse(response -> {
            if (isSearchResponse(response)) {
                String requestId = requestIdOf(response.url());
                synchronized (seenRequestIds) {
                    if (requestId != null && !seenRequestIds.add(requestId)) {
                        return;
                    }
                }
                Map<String, Job51JobCard> byId = new HashMap<>();
                for (Job51JobCard card : Job51JobCard.parseList(safeBody(response))) {
                    byId.put(card.getJobId(), card);
                }
                if (!byId.isEmpty()) {
                    cache.set(byId);
                    responseSeq.incrementAndGet();
                    log.debug("【{}】拦截到 {} 个岗位实体 (requestId={})", keyword, byId.size(), requestId);
                }
            }
        });
        try {
            navigate(page, searchUrl, listener);
            if (isAccessVerification(page)) {
                listener.onProgress("【" + keyword + "】触发访问验证（阿里 WAF），跳过该关键词");
                return;
            }
            waitForNewResponse(responseSeq, RESPONSE_TIMEOUT_MS, stop);
            if (!waitForList(page)) {
                if (hasNoJobHint(page)) {
                    listener.onProgress("【" + keyword + "】没有符合条件的职位");
                } else {
                    listener.onProgress("【" + keyword + "】列表未加载出来（可能被登录或验证挡住），跳过");
                }
                return;
            }

            int processed = 0;
            for (int pageIndex = 1; pageIndex <= MAX_PAGE; pageIndex++) {
                if (stop.getAsBoolean()) {
                    listener.onProgress("收到停止指令，中断采集（已处理 " + processed + " 个）");
                    break;
                }
                if (pageIndex > 1) {
                    if (!jumpToPage(page, pageIndex, listener, stop)) {
                        listener.onProgress("【" + keyword + "】已到最后一页");
                        break;
                    }
                    waitForNewResponse(responseSeq, RESPONSE_TIMEOUT_MS, stop);
                }
                closeAnyModalOverlays(page);
                if (isAccessVerification(page)) {
                    listener.onProgress("【" + keyword + "】触发访问验证（阿里 WAF），跳过该关键词");
                    return;
                }
                int count = applyButtonCount(page);
                if (count == 0) {
                    if (hasNoJobHint(page)) {
                        listener.onProgress("【" + keyword + "】没有符合条件的职位");
                    }
                    break;
                }
                Map<String, Job51JobCard> entities = cache.get();
                listener.onProgress("【" + keyword + "】第 " + pageIndex + " 页 " + count + " 个岗位");
                for (int i = 0; i < count; i++) {
                    if (stop.getAsBoolean() || processed >= maxCards) {
                        break;
                    }
                    try {
                        int live = applyButtonCount(page);
                        if (i >= live) {
                            log.debug("【{}】第 {} 个按钮已不在列表里", keyword, i + 1);
                            break;
                        }
                        Locator button = page.locator(APPLY_BUTTON).nth(i);
                        if (button.count() == 0) {
                            button = page.locator(APPLY_BUTTON_FALLBACK).nth(i);
                        }
                        if (button.count() == 0) {
                            continue;
                        }
                        String buttonText = safeText(button);
                        if (buttonText != null && isAlreadyApplied(buttonText)) {
                            continue;
                        }
                        if (isHijackedButton(button)) {
                            log.debug("【{}】第 {} 个是应届生外链，跳过", keyword, i + 1);
                            continue;
                        }
                        String jobId = readJobId(button);
                        if (jobId == null) {
                            log.debug("【{}】第 {} 个按钮取不到 jobId，跳过", keyword, i + 1);
                            continue;
                        }
                        Job51JobCard jobCard = entities.get(jobId);
                        if (jobCard == null) {
                            jobCard = cardFromDom(button, jobId);
                        }
                        currentButtonIndex = i;
                        processed++;
                        consumer.accept(jobCard, page);
                    } catch (Exception e) {
                        log.debug("【{}】处理第 {} 个岗位异常: {}", keyword, i + 1, e.getMessage());
                    }
                }
                if (processed >= maxCards) {
                    listener.onProgress("【" + keyword + "】已达单关键词上限 " + maxCards + " 个");
                    break;
                }
            }
            listener.onProgress("【" + keyword + "】处理完成，共 " + processed + " 个");
        } finally {
            currentButtonIndex = -1;
            closeQuietly(page);
        }
    }

    /**
     * 点一次排序下拉触发搜索接口。
     * <p>
     * <b>已废弃</b>：实测 {@code div.ss} 这个选择器在当前版搜索页上一个都匹配不到，
     * 点了等于没点；搜索接口进页面就自己发了，等 {@code waitForNewResponse} 足够。
     * 留着空方法只为不改动调用点结构，下个平台适配时直接删。
     */
    @SuppressWarnings("unused")
    private void warmUpSortDropdownRemoved() {
    }

    private int applyButtonCount(Page page) {
        try {
            int count = page.locator(APPLY_BUTTON).count();
            if (count == 0) {
                count = page.locator(APPLY_BUTTON_FALLBACK).count();
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    /** "已投递"/"已申请"/"记录"——这三个都说明点过了，不能重复投 */
    private static boolean isAlreadyApplied(String buttonText) {
        String t = buttonText.replace(" ", "");
        return t.contains("已投递") || t.contains("已申请") || t.contains("记录");
    }

    /**
     * 从投递按钮往上找 jobId。
     * <p>
     * 第一判据是卡片 div 上的 {@code sensorsdata} 属性——它是埋点用的 JSON，
     * 里面的 {@code jobId} 和搜索接口返回的数字 jobId 完全一致，是最可靠的来源。
     * <p>
     * 链接只作兜底：新版列表的 {@code a[href]} 全是公司页
     * （{@code jobs.51job.com/all/coXXXX.html}，co = company），不带 jobId；
     * 只有老格式的详情页链接（{@code /suzhou/166990732.html}、{@code ?jobId=}）才有。
     */
    private String readJobId(Locator button) {
        try {
            Object result = button.evaluate("el => {" +
                    "  const grab = (node) => {" +
                    "    if (!node || !node.getAttribute) return null;" +
                    "    const sd = node.getAttribute('sensorsdata');" +
                    "    if (sd && sd.indexOf('jobId') >= 0) return sd;" +
                    "    const anchors = node.querySelectorAll ? node.querySelectorAll('a[href]') : [];" +
                    "    for (const a of anchors) {" +
                    "      const href = a.getAttribute('href') || '';" +
                    "      if (/[?&]jobId=\\d+/.test(href) || /\\/\\d{5,}\\.html/.test(href)) return href;" +
                    "    }" +
                    "    return null;" +
                    "  };" +
                    "  let node = el;" +
                    "  for (let depth = 0; depth < 8 && node; depth++) {" +
                    "    const v = grab(node);" +
                    "    if (v) return v;" +
                    "    node = node.parentElement;" +
                    "  }" +
                    "  return null;" +
                    "}");
            if (result == null) {
                return null;
            }
            String raw = String.valueOf(result).trim();
            return raw.isBlank() || "null".equals(raw) ? null : jobIdFrom(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 sensorsdata JSON 或详情页链接里提数字 jobId。
     * <p>
     * sensorsdata 走 HTML 实体编码（{@code &quot;}），也可能带着内层转义引号，
     * 所以先归一化再正则提，不整段 JSON.parse——埋点字段随时会加，
     * 整段解析一碰到非标准转义就全废，正则只坏自己要的那个键。
     */
    static String jobIdFrom(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        Matcher m = Pattern.compile("\"jobId\"\\s*:\\s*\"?(\\d+)\"?").matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("[?&]jobId=(\\d+)").matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("jobId%3D(\\d+)", Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("/(\\d{5,})\\.html").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 接口没拦到时从按钮周边 DOM 兜底建卡。
     * <p>
     * sensorsdata 里带 jobTitle/jobSalary/jobArea/jobYear/jobDegree，比爬 DOM 文本全；
     * 公司名埋点里没有，单独从 {@code .cname} 取。
     */
    private Job51JobCard cardFromDom(Locator button, String jobId) {
        Job51JobCard card = new Job51JobCard();
        card.setJobId(jobId);
        try {
            Object result = button.evaluate("el => {" +
                    "  let node = el;" +
                    "  for (let depth = 0; depth < 8 && node; depth++) {" +
                    "    const sd = node.getAttribute ? node.getAttribute('sensorsdata') : null;" +
                    "    if (sd && sd.indexOf('jobId') >= 0) {" +
                    "      const cname = node.querySelector('.cname');" +
                    "      return sd + '\\u0001' + (cname ? (cname.innerText || '').trim() : '');" +
                    "    }" +
                    "    node = node.parentElement;" +
                    "  }" +
                    "  return null;" +
                    "}");
            if (result != null) {
                String raw = String.valueOf(result);
                int sep = raw.indexOf('\u0001');
                String sensors = sep >= 0 ? raw.substring(0, sep) : raw;
                String company = sep >= 0 ? raw.substring(sep + 1) : "";
                fillFromSensorsData(card, sensors);
                if (!company.isBlank()) {
                    card.setBrandName(company);
                }
            }
        } catch (Exception ignore) {
            // 兜底卡本来就字段不全，取不到就算了
        }
        if (isBlank(card.getJobName())) {
            card.setJobName("（接口未拦到，仅 DOM 兜底）");
        }
        return card;
    }

    /**
     * 从 sensorsdata JSON 里按候选键名抽字段。
     * 值都是短字符串，正则逐个提就够——整段 JSON.parse 一碰到埋点里的
     * 嵌套转义就全废，而正则只坏自己要的那一个键。
     */
    static void fillFromSensorsData(Job51JobCard card, String sensors) {
        if (card == null || sensors == null || sensors.isBlank()) {
            return;
        }
        String s = sensors.replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        setIfPresent(card::setJobName, firstMatch(s, "jobTitle", "jobName"));
        setIfPresent(card::setSalaryDesc, firstMatch(s, "jobSalary", "salary"));
        setIfPresent(card::setLocationRaw, firstMatch(s, "jobArea", "cityName"));
        Job51JobCard.splitLocation(card);
        setIfPresent(card::setJobExperience, firstMatch(s, "jobYear", "workYear"));
        setIfPresent(card::setJobDegree, firstMatch(s, "jobDegree", "degree"));
    }

    /** 埋点里没有这个键就保持原值，不用 null 把已有的覆盖掉 */
    private static void setIfPresent(java.util.function.Consumer<String> setter, String value) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private static String firstMatch(String json, String... keys) {
        for (String key : keys) {
            Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
            if (m.find()) {
                String value = m.group(1).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 预检按钮是不是应届生/校招外链。
     * 四层防御里最后一道：前面三层的 route abort / window.open 覆写 / onPopup
     * 都可能被平台的新交互绕过，点击前再查一次 closest('a') 最稳。
     */
    private boolean isHijackedButton(Locator button) {
        try {
            Object result = button.evaluate("el => {" +
                    "  const txt = (el.innerText || '').trim();" +
                    "  if (txt.includes('网申') || txt.includes('跳转') || txt.includes('去申请')) return true;" +
                    "  let node = el;" +
                    "  for (let depth = 0; depth < 8 && node; depth++) {" +
                    "    if (node.tagName === 'A') {" +
                    "      const href = node.getAttribute('href') || node.href || '';" +
                    "      if (href.includes('yingjiesheng') || href.includes('xyz.51job')" +
                    "          || href.includes('partner=51wspcjoblist')) return true;" +
                    "    }" +
                    "    const anchors = node.querySelectorAll ? node.querySelectorAll('a[href]') : [];" +
                    "    for (const a of anchors) {" +
                    "      const href = a.getAttribute('href') || a.href || '';" +
                    "      if (href.includes('yingjiesheng') || href.includes('xyz.51job')" +
                    "          || href.includes('partner=51wspcjoblist')) return true;" +
                    "    }" +
                    "    node = node.parentElement;" +
                    "  }" +
                    "  return false;" +
                    "}");
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 反外链劫持（应届生职位）
    // ------------------------------------------------------------------

    /**
     * 装四层防御。每层单独都可能被绕过，合起来才够：
     * <ol>
     *   <li>route abort：请求阶段就掐掉外链资源</li>
     *   <li>window.open 覆写：脚本级拦截新窗口</li>
     *   <li>onPopup/onPage：前两层漏了，弹出来 0ms 内关掉</li>
     *   <li>{@link #isHijackedButton}：点击前预检，前三个都来不及时最后一道</li>
     * </ol>
     */
    private void installHijackDefenses(Page page) {
        for (String marker : HIJACK_MARKERS) {
            page.route("**/*" + marker + "*/**", Route::abort);
        }
        page.onPopup(popup -> {
            log.debug("51job 弹出新窗口，立即关闭: {}", popup.url());
            closeQuietly(popup);
        });
        try {
            page.evaluate("() => {" +
                    "  const orig = window.open;" +
                    "  window.open = function(url, ...rest) {" +
                    "    if (typeof url === 'string' && (url.includes('yingjiesheng')" +
                    "        || url.includes('xyz.51job') || url.includes('partner=51wspcjoblist'))) {" +
                    "      return null;" +
                    "    }" +
                    "    return orig.call(window, url, ...rest);" +
                    "  };" +
                    "  document.querySelectorAll('a[href*=\"yingjiesheng\"], a[href*=\"xyz.51job\"]," +
                    "      a[href*=\"partner=51wspcjoblist\"]').forEach(a => {" +
                    "    a.removeAttribute('target');" +
                    "    a.style.pointerEvents = 'none';" +
                    "  });" +
                    "}");
        } catch (Exception e) {
            log.debug("window.open 覆写失败（页面可能刚导航完）: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 投递
    // ------------------------------------------------------------------

    /**
     * 投递一个岗位：点列表里的"投递"按钮 → 处理成功弹窗 → 关遮罩。
     * <p>
     * 51job 的投递按钮就在列表页，不用像 Boss/智联那样先开详情。
     * 预演模式只记录不点——但按钮状态照常读，这样预演也能发现"已投递"。
     */
    public DeliveryOutcome deliver(Job51JobCard card, Page listPage,
                                   Job51Properties.Job51Config config,
                                   ProgressListener listener, BooleanSupplier stop) {
        String target = (card.getBrandName() != null ? card.getBrandName() : "?")
                + " | " + (card.getJobName() != null ? card.getJobName() : "?");
        int index = currentButtonIndex;
        if (index < 0) {
            return DeliveryOutcome.failed("按钮下标丢失，无法定位投递按钮");
        }
        if (config.isDryRun()) {
            listener.onProgress("预演：跳过真实投递 | " + target);
            return DeliveryOutcome.preview(config.getSayHi());
        }
        if (stop.getAsBoolean()) {
            return DeliveryOutcome.failed("已停止");
        }
        try {
            Locator button = listPage.locator(APPLY_BUTTON).nth(index);
            if (button.count() == 0) {
                button = listPage.locator(APPLY_BUTTON_FALLBACK).nth(index);
            }
            if (button.count() == 0) {
                return DeliveryOutcome.failed("投递按钮已不在列表里（页面可能刷新过）");
            }
            String text = safeText(button);
            if (text != null && isAlreadyApplied(text)) {
                // 按钮已经变成"已投递"，平台侧就是投过了
                return new DeliveryOutcome(DeliveryStatus.DELIVERED, null, null);
            }
            if (isHijackedButton(button)) {
                return DeliveryOutcome.failed("应届生外链职位，已跳过");
            }
            if (isAccessVerification(listPage)) {
                return DeliveryOutcome.failed("触发访问验证（阿里 WAF），请稍后重试");
            }
            closeAnyModalOverlays(listPage);
            // 点之前记下按钮总数：投递成功后那一行的"投递"按钮会消失，总数少一个
            int buttonsBefore = applyButtonCount(listPage);
            clickWithForce(button);

            // 上限 toast 只闪一两秒，点击后立刻探
            for (int i = 0; i < 4 && !stop.getAsBoolean(); i++) {
                if (detectDailyLimit(listPage)) {
                    closeAnyModalOverlays(listPage);
                    return DeliveryOutcome.limit("触发每日投递上限");
                }
                sleep(250);
            }
            boolean success = handleResultDialog(listPage, target, listener);
            closeAnyModalOverlays(listPage);
            if (detectDailyLimit(listPage)) {
                return DeliveryOutcome.limit("触发每日投递上限");
            }
            if (!success) {
                success = waitForApplyConfirmed(listPage, index, buttonsBefore, stop);
            }
            if (!success) {
                return DeliveryOutcome.failed("未确认投递结果");
            }
            // 成功框是接口回来之后才渲染的：点完立刻关会扑空，留在这儿直到下一个岗位
            sleep(700);
            closeAnyModalOverlays(listPage);
            listener.onProgress("已投递 | " + target);
            return DeliveryOutcome.delivered(config.getSayHi());
        } catch (Exception e) {
            log.warn("投递过程异常 | {}: {}", target, e.getMessage());
            return DeliveryOutcome.failed(e.getMessage());
        }
    }

    /**
     * 投递后等确认。51job 投递成功不给弹窗，给的是顶部一个"投递成功"提示条
     * （自定义 toast，不是 el-dialog/van-popup，所以 {@link #handleResultDialog} 认不出来），
     * 外加那一行的"投递"按钮消失。三个信号任中其一即算投过：
     * <ol>
     *   <li>页面上出现可见的"投递成功"文本</li>
     *   <li>投递按钮总数比点击前少——那一行的按钮被撤了</li>
     *   <li>原下标的按钮变成"已投递/已申请"，或不再是投递按钮</li>
     * </ol>
     * 必须轮询：提示条有出现延迟，按钮状态也要等接口回来才变，点完立刻看一眼全判失败。
     */
    private boolean waitForApplyConfirmed(Page page, int index, int buttonsBefore,
                                          BooleanSupplier stop) {
        for (int i = 0; i < 20 && !stop.getAsBoolean(); i++) {
            try {
                Locator hit = page.locator("text=投递成功").first();
                if (hit.count() > 0 && hit.isVisible()) {
                    log.debug("51job 投递成功提示已出现");
                    return true;
                }
            } catch (Exception ignore) {
                // 页面在刷新，下一轮再试
            }
            try {
                if (applyButtonCount(page) < buttonsBefore) {
                    log.debug("51job 投递按钮已消失（{} → {}）", buttonsBefore, applyButtonCount(page));
                    return true;
                }
            } catch (Exception ignore) {
            }
            try {
                String after = applyButtonTextAt(page, index);
                // "已投递"里含"投递"，所以不能只判"不再是投递"：那样按钮明明已经
                // 变成已投递还算没确认，同一岗位当天会被反复真投（实测投了 4 次）。
                if (after != null && (isAlreadyApplied(after) || !after.contains("投递"))) {
                    log.debug("51job 投递按钮文本已变为：{}", after);
                    return true;
                }
            } catch (Exception ignore) {
            }
            sleep(400);
        }
        return false;
    }

    /** 按下标取投递按钮文本；按钮不在列表里返回 null */
    private String applyButtonTextAt(Page page, int index) {
        if (index < 0) {
            return null;
        }
        Locator b = page.locator(APPLY_BUTTON).nth(index);
        if (b.count() == 0) {
            b = page.locator(APPLY_BUTTON_FALLBACK).nth(index);
            if (b.count() == 0) {
                return null;
            }
        }
        return safeText(b);
    }

    /**
     * 处理投递结果弹窗。返回是否看到成功提示。
     * <p>
     * 51job 的成功框有两套（Vant 的 van-popup 和 ElementUI 的 el-dialog），
     * 文案也不固定（"投递成功N个"/"成功"/"已申请"），所以按关键词模糊判。
     */
    private boolean handleResultDialog(Page page, String target, ProgressListener listener) {
        for (int i = 0; i < 6; i++) {
            try {
                Locator dialog = page.locator(
                        ".el-dialog, .el-message-box, .van-popup, .van-dialog, [role='dialog']").first();
                if (dialog.count() == 0 || !dialog.isVisible()) {
                    sleep(300);
                    continue;
                }
                String text = dialog.innerText(new Locator.InnerTextOptions()
                        .setTimeout(ACTION_TIMEOUT_MS));
                if (text == null) {
                    sleep(300);
                    continue;
                }
                if (text.contains("投递成功") || text.contains("成功") || text.contains("已申请")) {
                    // 弹窗可能带"继续沟通"之类的副按钮，关掉就行
                    Locator close = dialog.locator(
                            "button.el-dialog__headerbtn, button[aria-label='Close'], .van-popup__close-icon"
                    ).first();
                    if (close.count() > 0) {
                        close.click(new Locator.ClickOptions()
                                .setTimeout(ACTION_TIMEOUT_MS).setForce(true));
                    }
                    listener.onProgress("投递成功弹窗已确认 | " + target);
                    return true;
                }
                if (text.contains("简历")) {
                    // 简历选择框：没有预设默认简历时会出现，勾上默认再确认
                    handleResumeDialog(page, dialog);
                    return true;
                }
                // 无关弹窗（扫码下载 App 之类），关掉继续
                closeAnyModalOverlays(page);
                return false;
            } catch (Exception e) {
                log.debug("处理结果弹窗异常: {}", e.getMessage());
                sleep(300);
            }
        }
        return false;
    }

    /** 简历选择弹窗：勾"每次投递默认发送该简历"再确认，省得每次弹 */
    private void handleResumeDialog(Page page, Locator dialog) {
        try {
            Locator remember = dialog.locator(
                    "text=每次投递默认发送该简历, label:has-text('默认')"
            ).first();
            if (remember.count() > 0) {
                remember.click(new Locator.ClickOptions()
                        .setTimeout(ACTION_TIMEOUT_MS).setForce(true));
            }
            Locator confirm = dialog.locator(
                    "button:has-text('确定'), button:has-text('确认'), button:has-text('投递简历')"
            ).first();
            if (confirm.count() > 0) {
                confirm.click(new Locator.ClickOptions()
                        .setTimeout(ACTION_TIMEOUT_MS).setForce(true));
            }
        } catch (Exception e) {
            log.debug("处理简历选择弹窗失败: {}", e.getMessage());
        }
    }

    /** 页面是否提示今日投递上限。命中就必须停，继续点只会全量失败 */
    public boolean detectDailyLimit(Page page) {
        try {
            for (String keyword : LIMIT_KEYWORDS) {
                Locator hit = page.locator("text=" + keyword);
                if (hit.count() > 0 && hit.first().isVisible()) {
                    return true;
                }
            }
            Locator toast = page.locator(
                    ".el-message, .el-message--info, .toast, .message, div[role='alert'], " +
                            ".el-notification__content, .van-toast, .van-notify");
            if (toast.count() > 0) {
                for (String text : toast.allInnerTexts()) {
                    if (text != null && containsAny(text, LIMIT_KEYWORDS)) {
                        return true;
                    }
                }
            }
            Object found = page.evaluate("() => {" +
                    "  const kws = ['今日投递太多','您今日投递太多','休息一下明天再来','达到上限','次数过多'];" +
                    "  const body = document.body ? (document.body.innerText || '') : '';" +
                    "  return kws.some(k => body.includes(k));" +
                    "}");
            return Boolean.TRUE.equals(found);
        } catch (Exception e) {
            return false;
        }
    }

    /** 阿里 WAF 的访问验证页。命中了重试只会更惨，直接放弃当前关键词 */
    private boolean isAccessVerification(Page page) {
        try {
            if (page.locator("//p[@class='waf-nc-title']").count() > 0) {
                return true;
            }
            if (page.locator("script[name^='aliyunwaf_']").count() > 0) {
                return true;
            }
            Locator text = page.locator("text=访问验证, text=请按住滑块");
            return text.count() > 0 && text.first().isVisible();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasNoJobHint(Page page) {
        try {
            for (String keyword : NO_JOB_KEYWORDS) {
                Locator hit = page.locator("text=" + keyword);
                if (hit.count() > 0 && hit.first().isVisible()) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 翻页（Element Plus 分页，三级降级）
    // ------------------------------------------------------------------

    private boolean jumpToPage(Page page, int pageNum, ProgressListener listener, BooleanSupplier stop) {
        for (int retry = 0; retry < 3 && !stop.getAsBoolean(); retry++) {
            try {
                closeAnyModalOverlays(page);
                // 1. 标准"下一页"按钮
                Locator next = page.locator(
                        "button.btn-next, .el-pagination button.btn-next, [aria-label='Next page']").first();
                if (next.count() == 0) {
                    next = page.locator("[class*='pagination'] [class*='next']").first();
                }
                if (next.count() > 0 && next.isEnabled()) {
                    next.click(new Locator.ClickOptions()
                            .setTimeout(ACTION_TIMEOUT_MS).setForce(true));
                    page.evaluate("window.scrollTo(0, 0)");
                    sleep(1500);
                    return true;
                }
                // 2. 页码数字
                Locator number = page.locator(
                        "ul.el-pager li.number:has-text('" + pageNum + "'), " +
                                "[class*='pagination'] li:has-text('" + pageNum + "')").first();
                if (number.count() > 0) {
                    number.click(new Locator.ClickOptions()
                            .setTimeout(ACTION_TIMEOUT_MS).setForce(true));
                    page.evaluate("window.scrollTo(0, 0)");
                    sleep(1500);
                    return true;
                }
                // 3. 跳页输入框
                Locator input = page.locator(
                        ".el-pagination__jump input, #jump_page, input[aria-label*='页']").first();
                if (input.count() > 0) {
                    input.click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
                    input.fill("");
                    input.fill(String.valueOf(pageNum));
                    input.press("Enter");
                    page.evaluate("window.scrollTo(0, 0)");
                    sleep(1500);
                    return true;
                }
                return false;
            } catch (Exception e) {
                log.debug("翻到第 {} 页失败，重试第 {} 次: {}", pageNum, retry + 1, e.getMessage());
                sleep(800);
            }
        }
        return false;
    }

    private boolean waitForList(Page page) {
        for (int i = 0; i < 15; i++) {
            try {
                if (applyButtonCount(page) > 0) {
                    return true;
                }
            } catch (Exception ignore) {
            }
            sleep(1000);
        }
        return false;
    }

    /**
     * 关掉一切可能吃掉点击的弹窗和遮罩。
     * Vant 和 ElementUI 两套控件混用，所以两套选择器都列。
     */
    private void closeAnyModalOverlays(Page page) {
        for (int round = 0; round < 3; round++) {
            boolean closedThisRound = false;
            try {
                Locator close = page.locator(
                        "button.el-dialog__headerbtn, button[aria-label='Close'], " +
                                "i.el-dialog__close.el-icon.el-icon-close, .van-popup__close-icon, " +
                                ".van-icon-cross, [class*='subscribe-close'], " +
                                "[class*='close']:not(body):not(html)").first();
                if (close.count() > 0 && close.isVisible()) {
                    close.click(new Locator.ClickOptions()
                            .setTimeout(3000).setForce(true));
                    closedThisRound = true;
                }
            } catch (Exception ignore) {
            }
            // 兜底：真按钮点了没反应（或压根没有）时按层剥掉。
            // 必须先点按钮，因为那是站点自己的关闭路径；直接 remove 的层
            // 会被 Vue 下一次渲染重新画出来。
            List<String> removed = removeStuckLayers(page);
            if (!removed.isEmpty()) {
                log.info("51job 剥离残留遮罩: {}", removed);
                closedThisRound = true;
            }
            if (!closedThisRound) {
                return;
            }
            sleep(500);
        }
    }

    /**
     * 剥掉关不掉的整屏层，返回被剥掉的层描述（用于日志）。
     *
     * <p>51job 投递后的"投递成功 + 微信扫一扫与HR沟通"推广框不给标准关闭按钮：
     * 既不是 {@code el-dialog} 也没有 {@code *mask}/{@code *overlay} 命名，
     * 原来的选择器一条都不命中，于是它一直糊在屏幕上——投递虽然还能继续（点击是
     * force 的），用户看到的就是"卡住了"。
     *
     * <p>判定条件刻意收得很紧，必须同时满足：定位 fixed/absolute、肉眼可见、
     * 尺寸够大、<em>而且</em>文案里有招聘推广的关键词。顶栏和侧边悬浮条都是
     * fixed 但没这些文案，所以不会被误删。
     */
    private List<String> removeStuckLayers(Page page) {
        String script = "() => {"
                + "  const hit = [];"
                + "  const area = window.innerWidth * window.innerHeight * 0.25;"
                + "  for (const el of document.querySelectorAll('div, section, aside')) {"
                + "    const st = getComputedStyle(el);"
                + "    if (st.position !== 'fixed' && st.position !== 'absolute') continue;"
                + "    if (st.display === 'none' || st.visibility === 'hidden') continue;"
                + "    if (Number(st.opacity) === 0) continue;"
                + "    const r = el.getBoundingClientRect();"
                + "    if (r.width < 200 || r.height < 200 || r.width * r.height < area) continue;"
                + "    const txt = (el.innerText || '') + ' ' + (el.className || '');"
                + "    if (!/投递成功|微信扫一扫|与HR|扫码|下载App|下载 App/.test(txt)) continue;"
                + "    hit.push(el.tagName + '.' + String(el.className).slice(0, 48));"
                + "    el.remove();"
                + "  }"
                + "  return Array.from(new Set(hit)).slice(0, 6);"
                + "}";
        try {
            Object out = page.evaluate(script);
            if (out instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
        } catch (Exception e) {
            log.debug("剥离残留遮罩失败: {}", e.getMessage());
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    private static boolean isSearchResponse(Response response) {
        try {
            String url = response.url();
            if (url == null || !url.contains(SEARCH_API)) {
                return false;
            }
            return "GET".equalsIgnoreCase(response.request().method());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从 URL query 里取 requestId。
     * 同一次搜索会发多次请求（初始化、排序、翻页），requestId 是它们各自的标识。
     */
    private static String requestIdOf(String url) {
        if (url == null) {
            return null;
        }
        int query = url.indexOf('?');
        if (query < 0) {
            return null;
        }
        for (String part : url.substring(query + 1).split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && "requestId".equals(part.substring(0, eq))) {
                try {
                    return java.net.URLDecoder.decode(part.substring(eq + 1),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return part.substring(eq + 1);
                }
            }
        }
        return null;
    }

    private static String safeBody(Response response) {
        try {
            return response.text();
        } catch (Exception e) {
            // 响应体读不了（已销毁等）：本页按空数据处理
            return "{}";
        }
    }

    /**
     * 等一次"新"的搜索接口响应。
     * 翻页后先记下当前序号，等到它变了才说明新一页的数据到了。
     */
    private void waitForNewResponse(AtomicLong seq, int timeoutMs, BooleanSupplier stop) {
        long before = seq.get();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !stop.getAsBoolean()) {
            if (seq.get() != before) {
                return;
            }
            sleep(300);
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

    private void configureTimeouts(Page page) {
        try {
            page.setDefaultNavigationTimeout(NAV_TIMEOUT_MS);
            page.setDefaultTimeout(ACTION_TIMEOUT_MS);
        } catch (Exception ignore) {
        }
    }

    /** force click：列表里按钮常被卡片容器裁切，普通 click 会因不可点而超时 */
    private void clickWithForce(Locator button) {
        try {
            button.hover(new Locator.HoverOptions().setTimeout(ACTION_TIMEOUT_MS));
        } catch (Exception ignore) {
            // hover 失败不影响点击
        }
        button.click(new Locator.ClickOptions()
                .setTimeout(ACTION_TIMEOUT_MS).setForce(true));
    }

    private static String safeText(Locator locator) {
        try {
            String text = locator.innerText(new Locator.InnerTextOptions()
                    .setTimeout(ACTION_TIMEOUT_MS));
            return text == null ? null : text.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean containsAny(String text, String[] keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
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
