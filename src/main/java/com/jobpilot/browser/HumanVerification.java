package com.jobpilot.browser;

import com.jobpilot.delivery.ProgressListener;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;

import java.util.function.BooleanSupplier;

/**
 * 人机验证页（滑块、图形验证码、"访问过于频繁"）的识别与等待。各平台共用。
 *
 * <p>2026-10 实测：拉勾、58 同城、应届生求职网在短时间连开几页后都会把整页
 * 换成验证页——拉勾和应届生是阿里滑块（标题「滑动验证页面」），58 跳到
 * {@code callback.58.com/antibot/verifycode}。这类验证<b>脚本不能也不该代过</b>，
 * 唯一的出路是停下来让用户在已经打开的浏览器窗口里自己拖一下。
 *
 * <p>老做法（51job）是一见验证就跳过当前关键词，结果是下一个关键词进来还是
 * 验证页，整轮空跑。改成原地等用户：过了就接着跑，超时才放弃。
 */
@Slf4j
public final class HumanVerification {

    private static final String[] URL_MARKERS = {
            "antibot", "verifycode", "captcha", "/verify", "security-check", "punish"
    };
    private static final String[] TITLE_MARKERS = {
            "滑动验证", "访问验证", "请输入验证码", "安全验证", "人机验证", "验证码"
    };
    private static final String[] TEXT_MARKERS = {
            "请按住滑块", "拖动到最右边", "访问过于频繁", "请完成安全验证",
            "请在五分钟内完成验证", "请完成下方验证", "拖动滑块完成拼图"
    };
    /** 只看正文开头：验证页正文很短，整页长文里碰巧出现"验证码"三个字不能算 */
    private static final int BODY_HEAD = 300;

    private HumanVerification() {
    }

    /** 纯函数版判定，拆出来单测 */
    static boolean looksLikeVerification(String url, String title, String bodyHead) {
        String u = url == null ? "" : url.toLowerCase();
        for (String marker : URL_MARKERS) {
            if (u.contains(marker)) {
                return true;
            }
        }
        String t = title == null ? "" : title;
        for (String marker : TITLE_MARKERS) {
            if (t.contains(marker)) {
                return true;
            }
        }
        String b = bodyHead == null ? "" : bodyHead;
        for (String marker : TEXT_MARKERS) {
            if (b.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 当前页面是不是验证页 */
    public static boolean isShowing(Page page) {
        try {
            Object head = page.evaluate("n => (document.body ? document.body.innerText || '' : '').slice(0, n)",
                    BODY_HEAD);
            return looksLikeVerification(page.url(), page.title(), head == null ? null : String.valueOf(head));
        } catch (Exception e) {
            // 页面正在跳转：读不到就当不是，下一次检查再说
            return false;
        }
    }

    /**
     * 是验证页就原地等用户手动过，不是就立刻返回 true。
     *
     * @return true = 当前没有验证（或用户已经过了）；false = 超时或用户点了停止
     */
    public static boolean waitIfShowing(Page page, String platformName, int timeoutMinutes,
                                        ProgressListener listener, BooleanSupplier stop) {
        if (!isShowing(page)) {
            return true;
        }
        int minutes = Math.max(1, timeoutMinutes);
        listener.onProgress("「" + platformName + "」弹出了人机验证（滑块/验证码），请在浏览器窗口里手动完成，"
                + minutes + " 分钟内有效。完成后会自动继续");
        log.info("{} 进入人机验证等待 | url={}", platformName, safeUrl(page));
        long deadline = System.currentTimeMillis() + minutes * 60_000L;
        while (System.currentTimeMillis() < deadline && !stop.getAsBoolean()) {
            sleep(2000);
            if (!isShowing(page)) {
                listener.onProgress("人机验证已通过，继续");
                // 过了验证平台通常会跳回原页面，给它一点时间渲染
                sleep(1500);
                return true;
            }
        }
        listener.onProgress("「" + platformName + "」人机验证没有完成，本轮停止");
        return false;
    }

    private static String safeUrl(Page page) {
        try {
            return page.url();
        } catch (Exception e) {
            return "?";
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
