package com.jobpilot.browser;

import java.io.File;

/**
 * 判断当前机器有没有可用的 Chrome。
 *
 * <p>浏览器用例拿它做 assumeTrue：没有 Chrome 就<b>跳过</b>而不是失败。
 * CI 的 macos-latest 镜像默认不装 Chrome（windows-latest 装），
 * 之前这几个用例在 macOS runner 上是一溜 FAILED，看着像功能坏了，
 * 其实是环境没这个浏览器。
 */
public final class ChromeProbe {

    private ChromeProbe() {
    }

    public static boolean available() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return new File("/Applications/Google Chrome.app").exists()
                    || new File(System.getProperty("user.home"),
                            "Applications/Google Chrome.app").exists();
        }
        if (os.contains("win")) {
            String[] roots = {
                System.getenv("ProgramFiles"),
                System.getenv("ProgramFiles(x86)"),
                System.getenv("LocalAppData"),
            };
            for (String root : roots) {
                if (root == null) {
                    continue;
                }
                if (new File(root, "Google/Chrome/Application/chrome.exe").exists()) {
                    return true;
                }
            }
            return false;
        }
        return new File("/usr/bin/google-chrome").exists()
                || new File("/usr/bin/chromium").exists();
    }
}
