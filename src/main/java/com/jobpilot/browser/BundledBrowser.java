package com.jobpilot.browser;

import org.springframework.boot.system.ApplicationHome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 定位安装包中与驱动版本一致的 Chromium。 */
final class BundledBrowser {
    private BundledBrowser() {
    }

    static Path executable() {
        for (Path root : List.of(new ApplicationHome(BrowserManager.class).getDir().toPath().resolve("browser"),
                Path.of("build/bundled-browser"))) {
            Path executable = executable(root);
            if (executable != null) {
                return executable;
            }
        }
        return null;
    }

    static Path executable(Path root) {
        Path directory = root.toAbsolutePath().normalize();
        try {
            String relative = Files.readString(directory.resolve("executable.txt")).trim();
            Path executable = directory.resolve(relative).normalize();
            return executable.startsWith(directory) && Files.isRegularFile(executable) ? executable : null;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }
}
