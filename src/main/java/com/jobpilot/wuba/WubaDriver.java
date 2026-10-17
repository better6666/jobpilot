package com.jobpilot.wuba;

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

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 58 同城浏览器驱动：登录 → 逐关键词采集列表 → 点列表上的"申请"。
 *
 * <p>2026-10-17 登录后只读实测：
 * <ul>
 *   <li>登录态：passport 域下有 {@code PPU} cookie，页头有"退出"</li>
 *   <li>列表 {@code li.job_item}，一页 35 条左右，字段全在卡片上（见 {@link WubaJobCard}）</li>
 *   <li>翻页是 {@code a.next}（href 形如 /quanzhizhaopin/pn2/），按它的 href 导航</li>
 *   <li>匿名或访问太快会跳 {@code callback.58.com/antibot/verifycode}，交给 {@link HumanVerification}</li>
 * </ul>
 *
 * <p><b>"申请"之后的流程没有实测</b>：真点需要在用户账号上真投，必须由用户自己在软件里发起。
 * 所以这里按常见形态处理（按钮变"已申请"/出现申请成功提示 = 成功；弹了带"投递/确定"的框就点），
 * 判不出成功一律记失败；每次点完都把页面上弹出的东西写进日志，第一次真投后据此校准。
 */
@Slf4j
@Component
public class WubaDriver {

    private static final String DISPLAY_NAME = "58同城";
    private static final String ITEM = "li.job_item";
    private static final String NEXT = "a.next";
    private static final String LOGIN_URL = "https://passport.58.com/login";
    private static final String[] SUCCESS = {"申请成功", "投递成功", "已申请", "已投递"};
    private static final String[] LIMIT = {"今日申请次数", "申请次数已达上限", "今天已经申请", "达到上限"};
    private static final int NAV_TIMEOUT_MS = 45_000;
    private static final int ACTION_TIMEOUT_MS = 10_000;
    private static final int MAX_PAGE = 20;

    private final BrowserManager browserManager;
    private volatile int waitMinutes = 5;

    public WubaDriver(BrowserManager browserManager) {
        this.browserManager = browserManager;
    }

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------

    public LoginResult ensureLogin(String citySub, int timeoutMinutes, ProgressListener listener, BooleanSupplier stop) {
        waitMinutes = timeoutMinutes;
        if (isLoggedIn()) {
            listener.onProgress("已检测到 58 同城登录态");
            return LoginResult.LOGGED_IN;
        }
        Page page = browserManager.context().newPage();
        try {
            navigate(page, LOGIN_URL, listener);
            listener.onProgress("未登录 58 同城，请在浏览器窗口里登录（扫码或手机验证码，等待 " + timeoutMinutes + " 分钟）");
            long deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L;
            while (System.currentTimeMillis() < deadline && !stop.getAsBoolean()) {
                sleep(2000);
                if (isLoggedIn()) {
                    listener.onProgress("登录成功");
                    return LoginResult.LOGGED_IN;
                }
            }
            return LoginResult.TIMEOUT;
        } finally {
            closeQuietly(page);
        }
    }

    /** 58 的登录票据 cookie 叫 PPU，登录后在 passport / 各城市子站都带着 */
    boolean isLoggedIn() {
        try {
            return browserManager.context().cookies("https://passport.58.com").stream()
                    .anyMatch(c -> "PPU".equals(c.name) && c.value != null && !c.value.isBlank());
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 采集
    // ------------------------------------------------------------------

    public void processKeyword(String searchUrl, String keyword, String cityName, int maxCards,
                               ProgressListener listener, BooleanSupplier stop, CardConsumer<WubaJobCard> consumer) {
        Page page = browserManager.context().newPage();
        page.onPopup(WubaDriver::closeQuietly);
        try {
            navigate(page, searchUrl, listener);
            int processed = 0;
            for (int pageNum = 1; pageNum <= MAX_PAGE; pageNum++) {
                if (stop.getAsBoolean()) {
                    listener.onProgress("收到停止指令，中断采集（已处理 " + processed + " 个）");
                    break;
                }
                if (!HumanVerification.waitIfShowing(page, DISPLAY_NAME, waitMinutes, listener, stop)) {
                    return;
                }
                int count = waitForItems(page);
                if (count == 0) {
                    listener.onProgress("【" + keyword + "】" + (pageNum == 1 ? "没有搜到岗位" : "没有更多岗位"));
                    break;
                }
                listener.onProgress("【" + keyword + "】第 " + pageNum + " 页 " + count + " 个岗位");
                List<Map<String, Object>> rows = readItems(page);
                for (Map<String, Object> row : rows) {
                    if (stop.getAsBoolean() || processed >= maxCards) {
                        break;
                    }
                    WubaJobCard card = WubaJobCard.fromFields(row, cityName);
                    if (card == null) {
                        continue;
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
                String next = nextHref(page);
                if (next == null) {
                    listener.onProgress("【" + keyword + "】已到最后一页");
                    break;
                }
                navigate(page, next, listener);
            }
            listener.onProgress("【" + keyword + "】处理完成，共 " + processed + " 个");
        } finally {
            closeQuietly(page);
        }
    }

    /**
     * 一次读出整页卡片。<b>必须在页面里 JSON.stringify 再回 Java 解析</b>：直接返回对象数组时，
     * 实测 35 个不同岗位回到 Java 全变成了第一个（预演跑出来同一岗位处理了 10 遍）
     */
    private List<Map<String, Object>> readItems(Page page) {
        Object raw = page.evaluate("() => JSON.stringify([...document.querySelectorAll('li.job_item')].map(li => {"
                + "  const t = s => { const n = li.querySelector(s); return n ? n.innerText : null; };"
                + "  const a = li.querySelector('.job_name a'); const btn = li.querySelector('a.apply');"
                + "  const comp = li.querySelector('.comp_name a'); const im = li.querySelector('[data-zpim]');"
                + "  return { infoid: btn ? btn.getAttribute('infoid') : null, title: a ? a.innerText : null,"
                + "    href: a ? a.getAttribute('href') : null, salary: t('.job_salary'),"
                + "    company: comp ? (comp.getAttribute('title') || comp.innerText) : null,"
                + "    cate: t('.cate'), xueli: t('.xueli'), jingyan: t('.jingyan'),"
                + "    zpim: im ? im.getAttribute('data-zpim') : null, btn: btn ? btn.innerText : null };"
                + "}))");
        return parseRows(raw == null ? null : String.valueOf(raw));
    }

    static List<Map<String, Object>> parseRows(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private String nextHref(Page page) {
        try {
            Locator next = page.locator(NEXT).first();
            if (next.count() == 0) {
                return null;
            }
            String href = next.getAttribute("href");
            return href == null || href.isBlank() || href.startsWith("javascript") ? null : href;
        } catch (Exception e) {
            return null;
        }
    }

    private int waitForItems(Page page) {
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

    public DeliveryOutcome deliver(WubaJobCard card, Page listPage, WubaProperties.WubaConfig config,
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
            if (!HumanVerification.waitIfShowing(listPage, DISPLAY_NAME, config.getLoginTimeoutMinutes(), listener, stop)) {
                return DeliveryOutcome.failed("人机验证未完成");
            }
            Locator btn = listPage.locator("a.apply[infoid='" + card.getJobId() + "']").first();
            if (btn.count() == 0) {
                return DeliveryOutcome.failed("列表里找不到这个岗位的申请按钮（页面可能刷新过）");
            }
            String before = safeText(btn);
            if (before != null && (before.contains("已申请") || before.contains("已投递"))) {
                return new DeliveryOutcome(DeliveryStatus.DELIVERED, null, null);
            }
            int pagesBefore = browserManager.context().pages().size();
            btn.click(new Locator.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
            boolean confirmed = false;
            String lastShape = null;
            for (int i = 0; i < 25 && !stop.getAsBoolean(); i++) {
                sleep(400);
                String shape = overlayShape(listPage);
                if (shape != null && !shape.equals(lastShape)) {
                    // 第一次真投前没见过这些弹窗：每种形态都记一笔，后面据此校准
                    log.info("58 申请后页面弹出 | {} | {}", target, shape);
                    lastShape = shape;
                }
                String limit = bodyHit(listPage, LIMIT);
                if (limit != null) {
                    return DeliveryOutcome.limit("58 提示：" + limit);
                }
                String after = safeText(btn);
                if ((after != null && (after.contains("已申请") || after.contains("已投递")))
                        || bodyHit(listPage, new String[]{"申请成功", "投递成功"}) != null) {
                    listener.onProgress("已申请 | " + target);
                    closeOverlays(listPage);
                    return DeliveryOutcome.delivered(null);
                }
                if (bodyHit(listPage, new String[]{"请先登录", "登录后申请"}) != null) {
                    return DeliveryOutcome.failed("58 同城提示要先登录");
                }
                if (!confirmed && clickConfirm(listPage)) {
                    confirmed = true;
                    log.info("58 申请：点了弹窗里的确认按钮 | {}", target);
                }
            }
            if (browserManager.context().pages().size() > pagesBefore) {
                log.info("58 申请后开了新标签页 | {}", target);
            }
            closeOverlays(listPage);
            return DeliveryOutcome.failed("未确认申请结果");
        } catch (Exception e) {
            log.warn("58 投递异常 | {}: {}", target, e.getMessage());
            return DeliveryOutcome.failed(e.getMessage());
        }
    }

    /** 当前页面上浮着的弹层：文字 + 按钮，没有返回 null */
    private String overlayShape(Page page) {
        try {
            Object o = page.evaluate("() => { const out = [];"
                    + " for (const e of document.querySelectorAll('div, section')) {"
                    + "  const st = getComputedStyle(e);"
                    + "  if (!(st.position === 'fixed' || st.position === 'absolute') || e.offsetParent === null) continue;"
                    + "  if (parseInt(st.zIndex || '0') < 10 || e.getBoundingClientRect().width < 150) continue;"
                    + "  const text = (e.innerText || '').replace(/\\s+/g, ' ').trim(); if (text.length < 2) continue;"
                    + "  const btns = [...e.querySelectorAll('a, button, span')].filter(b => b.children.length === 0)"
                    + "    .map(b => (b.innerText || '').trim()).filter(t => t && t.length < 10).slice(0, 8);"
                    + "  out.push(e.className.toString().slice(0, 40) + ' :: ' + text.slice(0, 120) + ' :: ' + btns.join('/'));"
                    + "  if (out.length >= 3) break; }"
                    + " return out.length ? out.join(' || ') : null; }");
            return o == null ? null : String.valueOf(o);
        } catch (Exception e) {
            return null;
        }
    }

    /** 弹层里带"投递/确定/申请"字样的主按钮，点一次 */
    private boolean clickConfirm(Page page) {
        try {
            Object clicked = page.evaluate("() => {"
                    + " const re = /^(投递|确定|确认|确认投递|立即投递|确认申请|投递简历|发送)$/;"
                    + " for (const e of document.querySelectorAll('div, section')) {"
                    + "  const st = getComputedStyle(e);"
                    + "  if (!(st.position === 'fixed' || st.position === 'absolute') || e.offsetParent === null) continue;"
                    + "  if (parseInt(st.zIndex || '0') < 10) continue;"
                    + "  if (!/简历|申请|投递/.test(e.innerText || '')) continue;"
                    + "  const b = [...e.querySelectorAll('a, button, span')].filter(x => x.children.length === 0 && x.offsetParent !== null)"
                    + "    .find(x => re.test((x.innerText || '').trim()));"
                    + "  if (b) { b.click(); return true; } }"
                    + " return false; }");
            return Boolean.TRUE.equals(clicked);
        } catch (Exception e) {
            return false;
        }
    }

    private void closeOverlays(Page page) {
        try {
            page.keyboard().press("Escape");
            page.evaluate("() => [...document.querySelectorAll('[class*=close], .close, .icon-close')]"
                    + ".filter(e => e.offsetParent !== null).slice(0, 2).forEach(e => e.click())");
        } catch (Exception ignore) {
        }
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
