package com.jobpilot.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 打包版 driver 解压：driver 随 fat jar 分发，首次启动落到数据目录。
 * 用临时 jar 伪造 classpath 资源，不碰真实 patchright driver（几十 MB）。
 */
class PlaywrightDriverSupportTest {

    @TempDir
    Path tempDir;

    /** 造一个只含 patchright/ 资源（外加一个不相干条目）的 jar */
    private Path createDriverJar(String version, String cliJsContent) throws IOException {
        Path jar = tempDir.resolve("fake-driver-" + Math.abs(version.hashCode()) + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "patchright/cli.js", cliJsContent);
            put(out, "patchright/package.json",
                    "{\n  \"name\": \"patchright-core\",\n  \"version\": \"" + version + "\"\n}");
            put(out, "patchright/lib/core.js", "// core bundle\n");
            put(out, "patchright/browsers.json", "{}");
            //  fat jar 里真实存在的其他前缀：不该被解压出来
            put(out, "BOOT-INF/lib/playwright.jar", "not-a-driver");
            put(out, "com/jobpilot/JobPilotApplication.class", "fake");
        }
        return jar;
    }

    private void put(JarOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private URLClassLoader isolatedLoader(Path jar) throws IOException {
        // 父加载器用 platform：看不见测试 classpath 上真实的 build/resources/main/patchright
        return new URLClassLoader(new URL[]{jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
    }

    @Test
    void 从jar资源解压driver并带上版本标记() throws IOException {
        Path jar = createDriverJar("1.62.1", "// patchright cli\n");
        Path driverDir = tempDir.resolve("appdata/driver");

        Path packageDir;
        try (URLClassLoader loader = isolatedLoader(jar)) {
            packageDir = PlaywrightDriverSupport.extractFromClasspath(driverDir, loader);
        }

        assertThat(packageDir).isEqualTo(driverDir.resolve("package"));
        assertThat(packageDir.resolve("cli.js")).hasContent("// patchright cli\n");
        assertThat(packageDir.resolve("package.json")).exists();
        assertThat(packageDir.resolve("lib/core.js")).exists();
        assertThat(packageDir.resolve(".version")).hasContent("1.62.1");
        // 只解压 patchright/ 前缀，jar 里的其他内容不落地
        assertThat(driverDir.resolve("package").resolve("BOOT-INF")).doesNotExist();
        assertThat(packageDir.resolve("playwright.jar")).doesNotExist();
    }

    @Test
    void 版本一致时跳过重复解压() throws IOException {
        Path jar = createDriverJar("1.62.1", "// original\n");
        Path driverDir = tempDir.resolve("appdata2/driver");
        try (URLClassLoader loader = isolatedLoader(jar)) {
            assertThat(PlaywrightDriverSupport.extractFromClasspath(driverDir, loader)).isNotNull();
            // 模拟用户/旧版改动过解压结果：版本一致时不该被覆盖回去
            Files.writeString(driverDir.resolve("package/cli.js"), "// tampered\n");
            assertThat(PlaywrightDriverSupport.extractFromClasspath(driverDir, loader))
                    .isEqualTo(driverDir.resolve("package"));
        }
        assertThat(driverDir.resolve("package/cli.js")).hasContent("// tampered\n");
    }

    @Test
    void 版本变化时重新解压() throws IOException {
        Path oldJar = createDriverJar("1.62.1", "// old\n");
        Path newJar = createDriverJar("1.63.0", "// new\n");
        Path driverDir = tempDir.resolve("appdata3/driver");

        try (URLClassLoader loader = isolatedLoader(oldJar)) {
            assertThat(PlaywrightDriverSupport.extractFromClasspath(driverDir, loader)).isNotNull();
        }
        try (URLClassLoader loader = isolatedLoader(newJar)) {
            assertThat(PlaywrightDriverSupport.extractFromClasspath(driverDir, loader)).isNotNull();
        }
        assertThat(driverDir.resolve("package/cli.js")).hasContent("// new\n");
        assertThat(driverDir.resolve("package/.version")).hasContent("1.63.0");
    }

    @Test
    void classpath没有driver资源时返回null() throws IOException {
        Path jar = tempDir.resolve("no-driver.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "com/jobpilot/Foo.class", "fake");
        }
        try (URLClassLoader loader = isolatedLoader(jar)) {
            assertThat(PlaywrightDriverSupport.extractFromClasspath(tempDir.resolve("appdata4/driver"), loader))
                    .isNull();
        }
        assertThat(tempDir.resolve("appdata4")).doesNotExist();
    }

    @Test
    void packagejson缺失时按unknown处理不会反复解压() throws IOException {
        Path jar = tempDir.resolve("no-version.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "patchright/cli.js", "// no version info\n");
        }
        Path driverDir = tempDir.resolve("appdata5/driver");
        try (URLClassLoader loader = isolatedLoader(jar)) {
            assertThat(PlaywrightDriverSupport.extractFromClasspath(driverDir, loader)).isNotNull();
            Files.writeString(driverDir.resolve("package/cli.js"), "// tampered\n");
            assertThat(PlaywrightDriverSupport.extractFromClasspath(driverDir, loader)).isNotNull();
        }
        assertThat(driverDir.resolve("package/cli.js")).hasContent("// tampered\n");
    }

    @Test
    void 解压目录下残留的版本标记不匹配时以packagejson为准() throws IOException {
        Path jar = createDriverJar("2.0.0", "// v2\n");
        Path driverDir = tempDir.resolve("appdata6/driver");
        Files.createDirectories(driverDir.resolve("package"));
        // 半截解压：有 cli.js 但没版本标记（上次解压中断），必须重新解压补齐
        Files.writeString(driverDir.resolve("package/cli.js"), "// half\n");
        try (URLClassLoader loader = isolatedLoader(jar)) {
            assertThat(PlaywrightDriverSupport.extractFromClasspath(driverDir, loader))
                    .isEqualTo(driverDir.resolve("package"));
        }
        assertThat(driverDir.resolve("package/cli.js")).hasContent("// v2\n");
        assertThat(driverDir.resolve("package/lib/core.js")).exists();
    }

    /**
     * 全流程契约：认领到的必须是 driver 根目录——playwright 在它下一层找
     * package/cli.js 和 node，指成 package 目录本身浏览器就启动不了（这个层级
     * 被回归过，别再搞混）。同时验证认领时会把系统属性指到同一个目录，
     * 且版本一致时不重复解压。
     *
     * 用 playwright.cli.dir 指向临时目录来驱动：测试 JVM 的工作目录就是项目目录，
     * 走文件系统候选会认领到 build/patchright-driver 这个真实 driver，
     * 往里面写 node 和覆盖 cli.js 会直接污染开发态 driver（被坑过）。
     */
    @Test
    void ensureDriverDir返回含package和node的根目录并写入系统属性() throws IOException {
        Path driverDir = tempDir.resolve("configured/driver");
        Files.createDirectories(driverDir.resolve("package"));
        Files.writeString(driverDir.resolve("package/cli.js"), "// patchright cli\n");

        String originCli = System.getProperty(PlaywrightDriverSupport.CLI_DIR_PROPERTY);
        System.setProperty(PlaywrightDriverSupport.CLI_DIR_PROPERTY, driverDir.toString());
        try {
            Path claimed = PlaywrightDriverSupport.ensureDriverDir();

            assertThat(claimed).isEqualTo(driverDir);
            assertThat(claimed.resolve("package/cli.js")).isRegularFile();
            // 环境里另给了 node 时不会往 driver 目录里解压，那种情况由
            // PlaywrightNodeSupportTest 覆盖，这里只断言默认路径
            if (System.getenv("PLAYWRIGHT_NODEJS_PATH") == null
                    || System.getenv("PLAYWRIGHT_NODEJS_PATH").isBlank()) {
                // Windows 上叫 node.exe，没有 POSIX 可执行位，
                // Files.isExecutable 恒 false——所以只按文件名断言存在
                assertThat(claimed.resolve(nodeName())).exists();
            }
            assertThat(System.getProperty(PlaywrightDriverSupport.CLI_DIR_PROPERTY))
                    .isEqualTo(driverDir.toAbsolutePath().toString());

            // 第二次启动：版本一致，不重复解压
            Files.writeString(driverDir.resolve("package/cli.js"), "// tampered\n");
            assertThat(PlaywrightDriverSupport.ensureDriverDir()).isEqualTo(driverDir);
            assertThat(driverDir.resolve("package/cli.js")).hasContent("// tampered\n");
        } finally {
            if (originCli == null) {
                System.clearProperty(PlaywrightDriverSupport.CLI_DIR_PROPERTY);
            } else {
                System.setProperty(PlaywrightDriverSupport.CLI_DIR_PROPERTY, originCli);
            }
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
