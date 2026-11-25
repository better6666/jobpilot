package com.jobpilot.browser;

import com.google.gson.JsonObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.impl.TargetClosedError;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Playwright 生命周期 + 单线程调度。
 *
 * 两个设计约束：
 * 1. Playwright Java 客户端不是线程安全的，所有调用必须串行。投递任务、登录监控、
 *    状态查询如果各起线程同时摸 page，会随机抛 "Target closed" / frame detached。
 *    这里用单线程 executor 收口：外部一律 submit()，dispatcher 线程内随便调。
 * 2. 浏览器上下文必须是持久化的（launchPersistentContext + 真实 Chrome 通道）：
 *    登录态、风控画像都活在 profile 里，关掉重开就等于每天重新过验证码。
 */
@Slf4j
@Component
public class BrowserManager {

    private final BrowserProperties properties;
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "jobpilot-browser");
        thread.setDaemon(true);
        return thread;
    });

    private Playwright playwright;
    private volatile BrowserContext context;
    /** 上一次 submit 超时后上下文可能已损坏，置位后下次启动前重建 */
    private volatile boolean contextPoisoned;

    public BrowserManager(BrowserProperties properties) {
        this.properties = properties;
    }

    /** 浏览器相关部署级配置，对应 application.yaml 的 browser.* 段。 */
    @Data
    @ConfigurationProperties(prefix = "browser")
    public static class BrowserProperties {
        /** true 时无头运行。Boss 投递必须 false：扫码登录、滑块验证都要真人看窗口 */
        private boolean headless = false;
        /** 每个动作之间的额外停顿（毫秒），模拟真人节奏 */
        private int slowMoMs = 50;
        /** auto 优先保留已有浏览器登录资料，新安装优先使用随包 Chromium。 */
        private String channel = "auto";
        /** 无头模式的视口尺寸。 */
        private int width = 1440;
        private int height = 900;
    }

    /** 在 dispatcher 线程里执行任务；超时后销毁上下文（Playwright 调用无法安全中断）。 */
    public <T> T submit(Callable<T> task, Duration timeout) {
        Future<T> future = dispatcher.submit(() -> {
            ensureStarted();
            return task.call();
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            contextPoisoned = true;
            throw new BrowserTimeoutException("浏览器操作超时（>" + timeout.toSeconds() + "s），上下文已标记重建");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new BrowserException("浏览器操作失败: " + cause, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrowserException("浏览器操作被中断", e);
        }
    }

    public boolean isContextAlive() {
        BrowserContext current = context;
        return current != null && !current.isClosed() && !contextPoisoned;
    }

    /**
     * 异步执行任务（不等结果）。投递跑批用：整个跑批可能持续几十分钟，
     * 不能占着调用线程；任务体仍在 dispatcher 线程上顺序执行，浏览器安全。
     */
    public Future<?> submitAsync(Runnable task) {
        return submitAsync(task, () -> {});
    }

    public Future<?> submitAsync(Runnable task, Runnable onStartFailure) {
        return dispatcher.submit(() -> {
            try {
                ensureStarted();
            } catch (RuntimeException e) {
                log.error("启动浏览器失败，任务未执行", e);
                onStartFailure.run();
                throw e;
            }
            task.run();
        });
    }

    public Path userDataDir(Path dataDir) {
        return dataDir.resolve("browser-data");
    }

    /** 销毁上下文（保留 dispatcher 线程）。下次 submit 时按需重建。 */
    public void resetContext() {
        try {
            dispatcher.submit(() -> {
                closeContextQuietly();
                contextPoisoned = false;
                return null;
            }).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("重置浏览器上下文异常: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            dispatcher.submit(() -> {
                closeContextQuietly();
                if (playwright != null) {
                    playwright.close();
                    playwright = null;
                }
                return null;
            }).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("关闭浏览器异常: {}", e.getMessage());
        } finally {
            dispatcher.shutdownNow();
        }
    }

    private void ensureStarted() {
        if (playwright == null) {
            PlaywrightDriverSupport.ensureDriverDir();
            playwright = Playwright.create();
            log.info("Playwright 已启动");
        }
        if (isContextAlive()) {
            try {
                List<Page> pages = context.pages();
                if (!pages.isEmpty()) {
                    // Java 在调用协议时才处理关闭事件，空闲时关窗后缓存状态可能仍显示存活。
                    context.newCDPSession(pages.getFirst()).detach();
                    return;
                }
            } catch (TargetClosedError e) {
                log.info("浏览器已关闭，重新打开");
            }
        }
        closeContextQuietly();
        context = launchContext();
        contextPoisoned = false;
        log.info("浏览器上下文已就绪");
    }

    private BrowserContext launchContext() {
        BrowserType.LaunchPersistentContextOptions options =
                new BrowserType.LaunchPersistentContextOptions()
                        .setHeadless(properties.isHeadless())
                        .setSlowMo((double) properties.getSlowMoMs())
                        .setLocale("zh-CN")
                        .setTimezoneId("Asia/Shanghai")
                        .setArgs(List.of(
                                "--disable-blink-features=AutomationControlled",
                                "--no-first-run",
                                "--no-default-browser-check",
                                "--disable-session-crashed-bubble"))
                        .setIgnoreDefaultArgs(List.of("--enable-automation"));
        if (properties.isHeadless()) {
            options.setViewportSize(properties.getWidth(), properties.getHeight());
        } else {
            // 扫码登录页按网页视口居中，视口超过实际窗口时登录框会被裁切。
            options.setViewportSize(null);
        }
        String channel = properties.getChannel();
        Path dataDir = com.jobpilot.system.SystemPaths.dataDir();
        Path bundled = BundledBrowser.executable();
        List<String> channels;
        if (channel == null || channel.isBlank() || "bundled".equals(channel)) {
            channels = List.of("bundled");
        } else if ("auto".equals(channel)) {
            boolean existingExternalProfile = Files.isRegularFile(userDataDir(dataDir).resolve("Local State"))
                    || Files.isRegularFile(dataDir.resolve("browser-data-msedge/Local State"));
            channels = bundled != null && !existingExternalProfile
                    ? List.of("bundled", "chrome", "msedge") : List.of("chrome", "msedge", "bundled");
        } else if ("chrome".equals(channel)) {
            channels = List.of("chrome", "msedge", "bundled");
        } else {
            channels = List.of(channel, "bundled");
        }
        RuntimeException lastError = null;
        for (String candidate : channels) {
            boolean useBundled = "bundled".equals(candidate);
            Path profile = switch (candidate) {
                case "chrome" -> userDataDir(dataDir);
                case "bundled" -> dataDir.resolve("browser-data-chromium");
                default -> dataDir.resolve("browser-data-" + candidate);
            };
            try {
                options.setChannel(useBundled ? null : candidate);
                options.setExecutablePath(useBundled ? bundled : null);
                BrowserContext launched = playwright.chromium().launchPersistentContext(profile, options);
                log.info("使用浏览器 {}", useBundled ? "Chromium" : candidate);
                return launched;
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("浏览器 {} 启动失败: {}", candidate, e.getMessage());
            }
        }
        throw new BrowserException("浏览器启动失败，请重新解压完整软件包，或安装 Chrome / Edge 后重试", lastError);
    }

    private void closeContextQuietly() {
        if (context != null) {
            try {
                context.close();
            } catch (Exception e) {
                log.warn("关闭浏览器上下文异常: {}", e.getMessage());
            }
            context = null;
        }
    }

    /** 当前上下文。只能在 dispatcher 线程（submit 的任务体内）调用。 */
    public BrowserContext context() {
        if (context == null) {
            throw new BrowserException("浏览器上下文未初始化");
        }
        return context;
    }

    /** 在当前登录上下文添加标签页，保留用户当前所在的标签页。 */
    public Page newBackgroundPage() {
        return newBackgroundPage(context());
    }

    public static Page newBackgroundPage(BrowserContext current) {
        Page anchor = current.pages().stream().filter(page -> !page.isClosed()).findFirst()
                .orElseThrow(() -> new BrowserException("浏览器窗口已关闭"));
        CDPSession session = current.newCDPSession(anchor);
        try {
            JsonObject target = session.send("Target.getTargetInfo").getAsJsonObject("targetInfo");
            JsonObject params = new JsonObject();
            String initialUrl = "about:blank#jobpilot-" + UUID.randomUUID();
            params.addProperty("url", initialUrl);
            params.addProperty("background", true);
            if (target.has("browserContextId")) params.add("browserContextId", target.get("browserContextId"));
            return current.waitForPage(new BrowserContext.WaitForPageOptions()
                    .setPredicate(page -> initialUrl.equals(page.url())).setTimeout(10_000),
                    () -> session.send("Target.createTarget", params));
        } finally {
            session.detach();
        }
    }

    public static class BrowserException extends RuntimeException {
        public BrowserException(String message) {
            super(message);
        }

        public BrowserException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class BrowserTimeoutException extends RuntimeException {
        public BrowserTimeoutException(String message) {
            super(message);
        }
    }
}
