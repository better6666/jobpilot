package com.jobpilot.system;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 运行时文件路径的唯一出处。
 *
 * 一律落用户目录而非进程工作目录：打包成 .app / exe 后用户双击启动，
 * 工作目录是 /（macOS）或 System32（Windows），相对路径写不动，
 * 实测报 /db: Read-only file system 直接启动失败。
 */
public final class SystemPaths {

    private static final String APP_NAME = "JobPilot";

    private SystemPaths() {
    }

    /** 数据库与运行时文件目录 */
    public static Path dataDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", APP_NAME);
        }
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Path.of(appData != null && !appData.isBlank() ? appData : home, APP_NAME);
        }
        return Path.of(home, ".local", "share", APP_NAME);
    }
}
