package com.jobpilot.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 启动后把本机页面在一个像软件的窗口里打开。
 *
 * JobPilot 的界面是内嵌 Tomcat 托管的本地网页，没有自己的原生窗口：直接
 * `open &lt;url&gt;` 会在默认浏览器里开一个带地址栏、标签页、前进后退的普通页面，
 * 用户看到的就是"一个网站"，Dock 里也只有一个通用 Java 图标。所以用 Chrome 的
 * 应用模式（`--app=`）开一个无边框窗口：没有地址栏和标签页，有独立的 Dock 图标，
 * 看起来就是原生软件。程序本来就要求本机装着 Chrome（application.yaml 的
 * channel=chrome，反检测最好），不引入新依赖；它用的还是用户自己的默认 profile，
 * 和自动化那边 `<数据目录>/browser-data` 的独立 profile 互不干扰。
 *
 * 两处实测结论，改之前先看：
 *   - macOS 的 `open` 必须带 `-n`。Chrome 已经在运行时，少了 `-n` 它只把已有窗口
 *     翻到前面，不会新开应用窗口
 *   - `--window-size` 对应用窗口无效（传 900x600 照样开 1200x822），别指望它控尺寸
 *
 * 打不开不影响启动：无头环境（CI、服务器）本来就没有浏览器，只记日志。
 * `jobpilot.app-window=false` 可退回"默认浏览器打开普通页面"的老行为。
 */
public class LocalPageOpener implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(LocalPageOpener.class);

    /** 等子进程退出的上限：这些命令都是"起来就返回"，卡住说明环境有问题，不能拖住启动 */
    private static final long LAUNCH_TIMEOUT_SECONDS = 5;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!Boolean.parseBoolean(event.getApplicationContext().getEnvironment()
                .getProperty("jobpilot.open-page", "true"))) {
            return;
        }
        int port = 9527;
        if (event.getApplicationContext() instanceof WebServerApplicationContext web) {
            port = web.getWebServer().getPort();
        }
        String url = "http://127.0.0.1:" + port + "/";
        try {
            if (Boolean.parseBoolean(event.getApplicationContext().getEnvironment()
                    .getProperty("jobpilot.app-window", "true"))
                    && openInAppWindow(url)) {
                log.info("已在应用窗口打开 {}", url);
                return;
            }
            openInBrowser(url);
            log.info("已在默认浏览器打开 {}", url);
        } catch (Exception e) {
            log.warn("自动打开页面失败，请手动访问 {}", url, e);
        }
    }

    /**
     * 用 Chrome 应用模式开无边框窗口。任何一条命令成功就返回 true；
     * Chrome 压根不在（所有候选都起不来）时返回 false，让调用方用默认浏览器兜底。
     */
    private static boolean openInAppWindow(String url) throws InterruptedException {
        for (List<String> command : appWindowCommands(url)) {
            Process process;
            try {
                process = new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
            } catch (IOException e) {
                // 这个可执行文件不在（PATH 里没有 google-chrome 之类），试下一条
                continue;
            }
            if (!process.waitFor(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                // 没在时限内退出：命令已经发出去了，窗口大概率正在起来。这时候按成功算，
                // 别再试下一条，否则会多开一个窗口
                log.info("打开应用窗口的命令 {} 超过 {} 秒没返回，按已启动处理",
                        command.get(0), LAUNCH_TIMEOUT_SECONDS);
                process.destroy();
                return true;
            }
            if (process.exitValue() == 0) {
                return true;
            }
        }
        return false;
    }

    private static List<List<String>> appWindowCommands(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<List<String>> commands = new ArrayList<>();
        if (os.contains("mac")) {
            commands.add(List.of("open", "-na", "Google Chrome", "--args", "--app=" + url));
        } else if (os.contains("win")) {
            for (String chrome : windowsChromePaths()) {
                commands.add(List.of("cmd", "/c", "start", "", chrome, "--app=" + url));
            }
        } else {
            for (String chrome : List.of("google-chrome", "chromium", "chromium-browser")) {
                commands.add(List.of(chrome, "--app=" + url));
            }
        }
        return commands;
    }

    /** Windows 上 Chrome 不一定在 PATH 里，按常见安装位置找；找不到就空列表，走浏览器兜底 */
    private static List<String> windowsChromePaths() {
        List<String> paths = new ArrayList<>();
        for (String env : new String[]{"ProgramFiles", "ProgramFiles(x86)", "ProgramW6432", "LocalAppData"}) {
            String base = System.getenv(env);
            if (base == null) {
                continue;
            }
            String exe = base + "\\Google\\Chrome\\Application\\chrome.exe";
            if (new File(exe).isFile()) {
                paths.add(exe);
            }
        }
        return paths;
    }

    private static void openInBrowser(String url) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        ProcessBuilder builder;
        if (os.contains("mac")) {
            builder = new ProcessBuilder("open", url);
        } else if (os.contains("win")) {
            // start 把第一个参数当标题，空串占位否则 URL 会被吞
            builder = new ProcessBuilder("cmd", "/c", "start", "", url);
        } else {
            builder = new ProcessBuilder("xdg-open", url);
        }
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.start();
    }
}
