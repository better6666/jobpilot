package com.jobpilot.system;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;

/** 桌面环境检测和 macOS 再次打开应用时的窗口唤起。 */
public final class DesktopSupport {

    private DesktopSupport() {
    }

    public static boolean supported() {
        return !GraphicsEnvironment.isHeadless();
    }

    public static void installReopenHandler(Runnable onReopen) {
        if (!Desktop.isDesktopSupported()) return;
        try {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) {
                desktop.setOpenFileHandler(event -> onReopen.run());
            }
        } catch (Exception ignored) {
            // 非 macOS 平台没有该回调，主窗口仍可正常显示。
        }
    }
}
