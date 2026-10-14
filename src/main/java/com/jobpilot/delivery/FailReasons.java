package com.jobpilot.delivery;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把驱动层抛上来的原始异常文字翻成人话再落库。
 *
 * <p>以前 Playwright 的异常原样进了 fail_reason，记录表里是一整段
 * {@code Error { message='Timeout 10000ms exceeded. name='TimeoutError stack=...}}，
 * 管理页上一行只显示 "Error {"，用户根本不知道发生了什么（本机库里有 14 条）。
 */
public final class FailReasons {

    private static final Pattern PW_MESSAGE = Pattern.compile("message='([^'\\n]+)");
    private static final Pattern NET_ERROR = Pattern.compile("net::(ERR_[A-Z_]+)");
    private static final int MAX_LEN = 120;

    private FailReasons() {
    }

    public static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        if (raw.contains("Timeout") && raw.contains("exceeded")) {
            return "页面操作超时（元素长时间没响应，可能是页面卡住或网络慢）";
        }
        if (raw.contains("has been closed") || raw.contains("Target closed")) {
            return "浏览器页面被关掉了（窗口被手动关闭或浏览器崩溃）";
        }
        Matcher net = NET_ERROR.matcher(raw);
        if (net.find()) {
            return "网络错误（" + net.group(1) + "），检查网络或代理";
        }
        String s = raw;
        Matcher m = PW_MESSAGE.matcher(s);
        if (m.find()) {
            s = m.group(1);
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > MAX_LEN ? s.substring(0, MAX_LEN) + "…" : s;
    }
}
