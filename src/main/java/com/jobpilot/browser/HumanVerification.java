package com.jobpilot.browser;

import com.jobpilot.delivery.PlatformConfig;
import com.jobpilot.delivery.ProgressListener;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.impl.TargetClosedError;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 识别可见的验证和账号限制，验证操作由用户在浏览器完成。 */
public final class HumanVerification {
    private static final String[] URL_MARKERS = {
            "antibot", "verifycode", "captcha", "/verify", "security-check", "punish"
    };
    private static final String[] TITLE_MARKERS = {
            "滑动验证", "访问验证", "请输入验证码", "安全验证", "人机验证", "验证码"
    };
    private static final String[] TEXT_MARKERS = {
            "请按住滑块", "拖动到最右边", "请完成安全验证", "请进行安全验证", "请先进行安全验证",
            "请在五分钟内完成验证", "请完成下方验证", "拖动滑块完成拼图", "请完成验证", "验证后继续",
            "请先完成验证", "请输入验证码", "点击进行验证", "立即验证", "请进行验证", "请完成身份验证"
    };
    private static final String[] RESTRICTION_MARKERS = {
            "账号异常", "账户异常", "账号存在异常", "账号状态异常", "账号行为异常", "账号被限制", "账号已封禁",
            "账号存在风险", "访问过于频繁", "操作过于频繁", "操作太频繁", "操作频繁", "请求过于频繁"
    };
    private static final String[] FAILED_VERIFICATION_MARKERS = {"验证失败", "验证不通过", "验证码错误"};
    private static final String SIGNALS = """
            () => {
              const visible = el => {
                const css = getComputedStyle(el);
                return el.getClientRects().length && css.visibility === 'visible' && css.opacity !== '0';
              };
              const overlays = '[role="dialog"], [aria-modal="true"], [class*="dialog"], [class*="popup"], [class*="modal"], [class*="toast"]';
              const texts = Array.from(document.querySelectorAll(overlays)).filter(visible)
                .map(e => (e.innerText || '').trim()).filter(t => t.length && t.length <= 1200);
              const frames = Array.from(document.querySelectorAll('iframe[src]')).filter(visible).map(e => e.src);
              const body = document.body?.innerText?.trim() || '';
              const head = body.slice(0, 300);
              return [document.title, head, texts, frames];
            }
            """;

    private HumanVerification() { }

    enum State { CLEAR, CHALLENGE, RESTRICTED, UNREADABLE }

    public static final class PausedRun extends RuntimeException {
        private final boolean actionUnconfirmed;

        public PausedRun(String message) { this(message, false); }

        private PausedRun(String message, boolean actionUnconfirmed) {
            super(message);
            this.actionUnconfirmed = actionUnconfirmed;
        }

        public boolean actionUnconfirmed() { return actionUnconfirmed; }

        public PausedRun afterAction() {
            return new PausedRun(getMessage() + "。本岗位的操作结果请到平台核对", true);
        }
    }

    static boolean looksLikeVerification(String url, String title, String bodyHead) {
        return verificationUrl(url) || contains(title, TITLE_MARKERS) || contains(bodyHead, TEXT_MARKERS);
    }

    static boolean verificationUrl(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            URI url = URI.create(raw);
            return contains((url.getHost() + " " + url.getPath()).toLowerCase(Locale.ROOT), URL_MARKERS);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean contains(String value, String[] markers) {
        if (value == null) return false;
        for (String marker : markers) if (value.contains(marker)) return true;
        return false;
    }

    static State inspect(Page page) {
        if (page.isClosed()) throw new TargetClosedError();
        if (verificationUrl(page.url())) return State.CHALLENGE;
        try {
            List<?> signals = (List<?>) page.evaluate(SIGNALS);
            String title = String.valueOf(signals.get(0));
            String body = String.valueOf(signals.get(1));
            List<?> dialogs = (List<?>) signals.get(2);
            List<?> frames = (List<?>) signals.get(3);
            if (looksLikeVerification(null, title, body)
                    || dialogs.stream().anyMatch(t -> contains(String.valueOf(t), TEXT_MARKERS)
                            || contains(String.valueOf(t), FAILED_VERIFICATION_MARKERS))
                    || frames.stream().anyMatch(src -> verificationUrl(String.valueOf(src)))) return State.CHALLENGE;
            if (contains(title, RESTRICTION_MARKERS) || contains(body, RESTRICTION_MARKERS)
                    || dialogs.stream().anyMatch(t -> contains(String.valueOf(t), RESTRICTION_MARKERS))) return State.RESTRICTED;
            return body.isBlank() ? State.UNREADABLE : State.CLEAR;
        } catch (TargetClosedError e) {
            throw e;
        } catch (RuntimeException e) {
            return State.UNREADABLE;
        }
    }

    public static boolean isShowing(Page page) {
        State state = inspect(page);
        return state == State.CHALLENGE || state == State.RESTRICTED;
    }

    /** 保留现有平台的等待期限，仅在页面稳定恢复后继续。 */
    public static boolean waitIfShowing(Page page, String platformName, int timeoutMinutes,
                                        ProgressListener listener, BooleanSupplier stop) {
        try {
            waitForUser(() -> List.of(page), platformName, listener, stop,
                    Math.max(1, timeoutMinutes) * 60, 0, true);
            return !stop.getAsBoolean();
        } catch (PausedRun e) {
            listener.onProgress(e.getMessage());
            return false;
        }
    }

    /** BOSS 保留验证窗口，等待用户完成或主动停止；恢复前留出冷却时间。 */
    public static boolean waitForUser(Supplier<List<Page>> pages, String platformName,
                                      ProgressListener listener, BooleanSupplier stop) {
        return waitForUser(pages, platformName, listener, stop, 0, PlatformConfig.MIN_DELIVERY_WAIT_SECONDS);
    }

    static boolean waitForUser(Supplier<List<Page>> pages, String platformName, ProgressListener listener,
                               BooleanSupplier stop, int timeoutSeconds, int cooldownSeconds) {
        return waitForUser(pages, platformName, listener, stop, timeoutSeconds, cooldownSeconds, false);
    }

    private static boolean waitForUser(Supplier<List<Page>> pages, String platformName, ProgressListener listener,
                                       BooleanSupplier stop, int timeoutSeconds, int cooldownSeconds,
                                       boolean automaticFocus) {
        Page pending = null;
        int waited = 0;
        int clearSeconds = 0;
        while (!stop.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
            List<Page> surfaces = pages.get();
            if (pending != null && pending.isClosed()) throw new PausedRun("验证窗口已关闭，请确认平台账号状态后重新开始");
            Page challenge = null;
            Page restricted = null;
            boolean readable = true;
            for (Page surface : surfaces) {
                if (surface.isClosed()) continue;
                State state;
                try {
                    state = inspect(surface);
                } catch (TargetClosedError e) {
                    if (surface == pending) throw new PausedRun("验证窗口已关闭，请确认平台账号状态后重新开始");
                    continue;
                }
                if (state == State.CHALLENGE) challenge = surface;
                if (state == State.RESTRICTED) restricted = surface;
                if (surface == pending && state == State.UNREADABLE) readable = false;
            }
            if (challenge != null) {
                if (pending != challenge || clearSeconds >= 2) {
                    if (automaticFocus) {
                        listener.onProgress("「" + platformName + "」已暂停，请在浏览器窗口手动完成验证，完成后继续");
                        challenge.bringToFront();
                    } else {
                        listener.waitingForBrowser(true);
                        listener.onProgress("等待手动验证，请点「打开浏览器处理」");
                    }
                }
                pending = challenge;
                clearSeconds = 0;
            } else if (restricted != null) {
                if (automaticFocus) restricted.bringToFront();
                throw new PausedRun("「" + platformName + "」提示账号异常或操作受限，浏览器页面已保留。请切到浏览器处理后再开始");
            } else if (pending == null) {
                return false;
            } else if (!readable) {
                clearSeconds = 0;
            } else {
                if (++clearSeconds == 2) {
                    if (!automaticFocus) listener.waitingForBrowser(false);
                    if (cooldownSeconds == 0) return true;
                    listener.onProgress("验证页面已恢复，等待 " + cooldownSeconds + " 秒后继续投递");
                }
                if (clearSeconds >= 2 + cooldownSeconds) {
                    listener.onProgress("验证后等待结束，继续处理岗位");
                    return true;
                }
            }
            if (timeoutSeconds > 0 && waited >= timeoutSeconds) throw new PausedRun("验证尚未完成，本次任务已结束");
            if (pending != null) {
                try {
                    if (!automaticFocus && listener.takeBrowserRequest()) pending.bringToFront();
                    pending.waitForTimeout(1000);
                } catch (TargetClosedError e) {
                    throw new PausedRun("验证窗口已关闭，请确认平台账号状态后重新开始");
                }
            }
            waited++;
        }
        if (pending == null) return false;
        throw new PausedRun("已停止，浏览器页面已保留。请在平台处理后重新开始");
    }
}
