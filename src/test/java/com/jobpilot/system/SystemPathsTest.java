package com.jobpilot.system;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据目录测试。
 *
 * 这是双击启动会不会崩的分界线：相对路径在 .app / exe 场景下必崩
 * （工作目录是 / 或 System32），所以路径必须绝对且落在用户目录。
 */
class SystemPathsTest {

    @Test
    void 数据目录是绝对路径() {
        assertTrue(SystemPaths.dataDir().isAbsolute());
    }

    @Test
    void 数据目录落在用户目录下且以应用名结尾() {
        Path dir = SystemPaths.dataDir();
        assertEquals("JobPilot", dir.getFileName().toString());
        // macOS 是 ~/Library/Application Support/JobPilot，Linux 是 ~/.local/share/JobPilot，
        // Windows 是 %APPDATA%/JobPilot；三者都在用户可写区域
        assertFalse(dir.toString().contains("/db"));
        assertFalse(dir.startsWith(Path.of("/")) && !dir.startsWith(Path.of(System.getProperty("user.home"))));
    }

    @Test
    void 同一进程内多次调用结果稳定() {
        assertEquals(SystemPaths.dataDir(), SystemPaths.dataDir());
    }
}
