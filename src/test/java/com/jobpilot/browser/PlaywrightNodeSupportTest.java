package com.jobpilot.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 打包态 node 可执行文件定位：用户机器上不装 node，playwright 只认
 * &lt;driverDir&gt;/node，所以要从 classpath 的 driver-bundle 里解压当前平台那份。
 */
class PlaywrightNodeSupportTest {

    @TempDir
    Path tempDir;

    private URLClassLoader bundleLoader(Path jar) throws IOException {
        // 父加载器用 platform：看不见测试 classpath 上真的 driver-bundle（100+MB）
        return new URLClassLoader(new URL[]{jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
    }

    private Path createBundleJar(String platformDir, String exe) throws IOException {
        Path jar = tempDir.resolve("bundle-" + Math.abs((platformDir + exe).hashCode()) + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("driver/" + platformDir + "/" + exe));
            out.write("fake-node-binary".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("driver/" + platformDir + "/package/cli.js"));
            out.write("// cli".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    @Test
    void 从driverBundle解压当前平台的node并置可执行权限() throws IOException {
        Path jar = createBundleJar(PlaywrightDriverSupport.nodePlatformDir(), "node");
        Path driverDir = tempDir.resolve("driver");

        boolean ok;
        try (URLClassLoader loader = bundleLoader(jar)) {
            ok = PlaywrightDriverSupport.ensureNodeExecutable(driverDir, loader);
        }

        assertThat(ok).isTrue();
        Path node = driverDir.resolve(nodeName());
        assertThat(node).hasContent("fake-node-binary");
        if (!isWindows()) {
            assertThat(Files.isExecutable(node)).isTrue();
        }
    }

    @Test
    void node已存在时不解压() throws IOException {
        Path jar = createBundleJar(PlaywrightDriverSupport.nodePlatformDir(), "node");
        Path driverDir = tempDir.resolve("driver");
        Files.createDirectories(driverDir);
        Files.writeString(driverDir.resolve("node"), "already-here");

        boolean ok;
        try (URLClassLoader loader = bundleLoader(jar)) {
            ok = PlaywrightDriverSupport.ensureNodeExecutable(driverDir, loader);
        }

        assertThat(ok).isTrue();
        assertThat(driverDir.resolve(nodeName())).hasContent("already-here");
    }

    @Test
    void classpath没有driverBundle时返回false() throws IOException {
        Path jar = tempDir.resolve("empty.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("com/jobpilot/Foo.class"));
            out.write("fake".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try (URLClassLoader loader = bundleLoader(jar)) {
            assertThat(PlaywrightDriverSupport.ensureNodeExecutable(tempDir.resolve("d"), loader))
                    .isFalse();
        }
    }

    @Test
    void 平台目录名与driverBundle发布布局一致() {
        String os = System.getProperty("os.name").toLowerCase();
        String expected;
        if (os.contains("mac")) {
            expected = System.getProperty("os.arch").toLowerCase().contains("aarch64")
                    ? "mac-arm64" : "mac";
        } else if (os.contains("win")) {
            expected = "win32_x64";
        } else {
            expected = System.getProperty("os.arch").toLowerCase().contains("aarch64")
                    ? "linux-arm64" : "linux";
        }
        assertThat(PlaywrightDriverSupport.nodePlatformDir()).isEqualTo(expected);
    }

    @Test
    void 平台目录名随os属性变化() {
        String originOs = System.getProperty("os.name");
        String originArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.setProperty("os.arch", "aarch64");
            assertThat(PlaywrightDriverSupport.nodePlatformDir()).isEqualTo("mac-arm64");

            System.setProperty("os.arch", "x86_64");
            assertThat(PlaywrightDriverSupport.nodePlatformDir()).isEqualTo("mac");

            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "amd64");
            assertThat(PlaywrightDriverSupport.nodePlatformDir()).isEqualTo("win32_x64");

            System.setProperty("os.name", "Linux");
            System.setProperty("os.arch", "aarch64");
            assertThat(PlaywrightDriverSupport.nodePlatformDir()).isEqualTo("linux-arm64");

            System.setProperty("os.name", "SunOS");
            assertThat(PlaywrightDriverSupport.nodePlatformDir()).isNull();
        } finally {
            System.setProperty("os.name", originOs);
            System.setProperty("os.arch", originArch);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Windows 上 node 可执行文件叫 node.exe，且没有 POSIX 可执行位 */
    private static String nodeName() {
        return isWindows() ? "node.exe" : "node";
    }
}
