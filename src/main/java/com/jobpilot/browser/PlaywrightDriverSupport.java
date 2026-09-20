package com.jobpilot.browser;

import com.jobpilot.system.SystemPaths;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * patchright driver 定位（含它依赖的 node 可执行文件）。
 *
 * playwright-java 通过系统属性 playwright.cli.dir 找 Node driver，要求目录里
 * 有 package/cli.js（patchright-core 的包内容）。四个来源按优先级：
 *   1. 启动参数 -Dplaywright.cli.dir（gradle bootRun 会带上）
 *   2. fat jar 同级的 driver/package —— 发布脚本把 driver 复制到
 *      .app/Contents/app/driver/ 或安装目录 driver/，双击启动也能用
 *   3. 当前目录 build/patchright-driver/package —— 开发态兜底
 *   4. classpath 里的 patchright/ 资源（build.gradle.kts 的 bundlePatchrightDriver
 *      打进 fat jar 的）——打包机没装 node 时的兜底：首次启动解压到数据目录，
 *      之后按版本号跳过，不重复拷贝
 *
 * 光有 driver 还不够：playwright 启动 driver 进程时找的是 &lt;driverDir&gt;/node
 * （或环境变量 PLAYWRIGHT_NODEJS_PATH），patchright 的 npm 包里没有 node，
 * 用户机器上也不会装。所以认领任一来源前，都要从 classpath 的 driver-bundle
 * （playwright 官方依赖，fat jar 里带全平台 node）把当前平台那份解压到 driver
 * 目录旁边；取不到 node 就宁可不认领——设了 cli.dir 却启动不了浏览器，
 * 比退回 playwright 自带 driver（功能可用、少反检测补丁）更糟。
 *
 * 都找不到就返回 null，playwright 会退回自带的 driver-bundle。
 */
@Slf4j
public final class PlaywrightDriverSupport {

    public static final String CLI_DIR_PROPERTY = "playwright.cli.dir";

    /** fat jar 里 driver 资源的路径前缀，与 bundlePatchrightDriver 的同步目标一致 */
    private static final String RESOURCE_PREFIX = "patchright/";

    /** 解压目录里的版本标记，内容取自 patchright/package.json 的 version */
    private static final String VERSION_MARKER = ".version";

    private static final Pattern VERSION_PATTERN = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    private PlaywrightDriverSupport() {
    }

    /** 找到可用 driver 目录并（必要时）写进系统属性；返回最终生效目录，找不到返回 null。 */
    public static Path ensureDriverDir() {
        String configured = System.getProperty(CLI_DIR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            Path dir = Path.of(configured);
            if (!isDriverDir(dir)) {
                log.warn("playwright.cli.dir 指向的目录不是有效 driver: {}", dir);
            } else {
                // 同样要认领：外部指定的目录也可能没有 node，缺了浏览器起不来
                Path absolute = dir.toAbsolutePath();
                if (claim(absolute)) {
                    log.info("已定位 patchright driver: {}", absolute);
                    return absolute;
                }
            }
        }
        for (Path candidate : candidates()) {
            if (!isDriverDir(candidate)) {
                continue;
            }
            // 归一成绝对路径再认领：候选是相对路径时，返回值、系统属性、
            // 下次启动的早退分支必须指向同一个目录
            Path absolute = candidate.toAbsolutePath();
            if (claim(absolute)) {
                log.info("已定位 patchright driver: {}", absolute);
                return absolute;
            }
        }
        // 打包版前三个候选都不存在（driver 在 jar 里而不是文件系统上），解压到数据目录
        Path extracted = extractFromClasspath(SystemPaths.dataDir().resolve("driver"),
                PlaywrightDriverSupport.class.getClassLoader());
        if (extracted != null && claim(extracted.getParent())) {
            log.info("已从 jar 资源解压 patchright driver: {}", extracted.getParent());
            return extracted.getParent();
        }
        log.warn("未找到可用的 patchright driver（driver 或 node 缺失），退回 playwright 自带 driver");
        return null;
    }

    /**
     * 确认这个 driver 根目录能真正跑起来再认领它。
     *
     * playwright 的 node 解析（见 driver-1.62.0 的 Driver#nodePath）：环境变量
     * PLAYWRIGHT_NODEJS_PATH 优先，否则直接用 &lt;driverDir&gt;/node，不看 PATH。
     * patchright 的 npm 包里没有 node 二进制，用户机器上也不会装 node，
     * 所以要从 driver-bundle（playwright 官方依赖，fat jar 里带着全平台 node）
     * 里把当前平台的那份解压到 driver 目录旁边。解压不出来就不能认领：
     * 设了 playwright.cli.dir 却启动不了浏览器，比退回自带 driver 更糟。
     */
    private static boolean claim(Path driverDir) {
        if (!ensureNodeExecutable(driverDir, PlaywrightDriverSupport.class.getClassLoader())) {
            log.warn("patchright driver 目录缺少可用的 node，放弃认领: {}", driverDir);
            return false;
        }
        System.setProperty(CLI_DIR_PROPERTY, driverDir.toAbsolutePath().toString());
        return true;
    }

    /**
     * 文件系统候选，全部是 driver 根目录（playwright 会在里面找 package/cli.js 和 node）：
     *   1. fat jar 同级的 driver/ —— 发布脚本复制到 .app/Contents/app/driver/ 或安装目录
     *   2. 当前目录 build/patchright-driver/ —— 开发态从仓库根目录启动时兜底
     *   3. 当前目录 driver/ —— 手动把 driver 放到工作目录旁
     */
    private static List<Path> candidates() {
        Path codeSourceDir = codeSourceDir();
        return List.of(
                codeSourceDir.resolve("driver"),
                Path.of("build/patchright-driver"),
                Path.of("driver")
        );
    }

    /** 运行中的 class 所在目录：开发态是 build/classes/java/main，打包态是 .app/Contents/app */
    private static Path codeSourceDir() {
        try {
            Path location = Path.of(PlaywrightDriverSupport.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(location) ? location : location.getParent();
            return dir == null ? Path.of(".") : dir;
        } catch (Exception e) {
            return Path.of(".");
        }
    }

    /** driver 根目录（含 package/ 的那层）是否有效 */
    private static boolean isDriverDir(Path dir) {
        return hasCliJs(dir == null ? null : dir.resolve("package"));
    }

    /** package/ 目录本身是否有效（解压流程里用的就是这一层） */
    private static boolean hasCliJs(Path packageDir) {
        return packageDir != null && Files.isRegularFile(packageDir.resolve("cli.js"));
    }

    // -----------------------------------------------------------------------
    // node 可执行文件（打包版用户机器上不装 node，从 driver-bundle 里取）
    // -----------------------------------------------------------------------

    /**
     * 保证 driverDir 下有当前平台的 node：已存在就直接用，否则从 classpath 的
     * driver-bundle 资源 driver/&lt;平台&gt;/node[.exe] 解压。PLAYWRIGHT_NODEJS_PATH
     * 已在环境里时 playwright 会优先用它，无需解压。
     */
    static boolean ensureNodeExecutable(Path driverDir, ClassLoader loader) {
        if (driverDir == null || loader == null) {
            return false;
        }
        String nodeFromEnv = System.getenv("PLAYWRIGHT_NODEJS_PATH");
        if (nodeFromEnv != null && !nodeFromEnv.isBlank()) {
            return true;
        }
        String platformDir = nodePlatformDir();
        if (platformDir == null) {
            log.warn("未知平台，无法确定 node 资源目录: os.name={} os.arch={}",
                    System.getProperty("os.name"), System.getProperty("os.arch"));
            return false;
        }
        String exe = platformDir.startsWith("win32") ? "node.exe" : "node";
        Path target = driverDir.resolve(exe);
        if (Files.isRegularFile(target)) {
            return true;
        }
        URL url = loader.getResource("driver/" + platformDir + "/" + exe);
        if (url == null) {
            log.warn("classpath 上没有 driver-bundle 的 node 资源（driver/{}/{}}）", platformDir, exe);
            return false;
        }
        try {
            Files.createDirectories(driverDir);
            copyStream(url.openStream(), target);
            if (!exe.equals("node.exe")) {
                // jar 资源不带 unix 权限位，解压出来必须自己补可执行权限
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            log.warn("解压 node 失败: {} -> {}", url, target, e);
            return false;
        }
    }

    /** 平台 → driver-bundle 里的目录名，与 playwright driver-bundle 的发布布局一致 */
    static String nodePlatformDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("mac")) {
            return arm ? "mac-arm64" : "mac";
        }
        if (os.contains("win")) {
            return "win32_x64";
        }
        if (os.contains("linux")) {
            return arm ? "linux-arm64" : "linux";
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // 从 classpath 解压（打包版路径）
    // -----------------------------------------------------------------------

    /**
     * 把 classpath 上 patchright/ 前缀的资源解压到 driverDir/package/，返回 package 目录；
     * classpath 上没有 driver 返回 null。版本号一致时跳过解压（重复启动只花一次 stat）。
     * 加锁是因为用户可能同时开两个实例，不能互相覆盖对方正在读的 driver。
     */
    static Path extractFromClasspath(Path driverDir, ClassLoader loader) {
        if (loader == null || loader.getResource(RESOURCE_PREFIX + "cli.js") == null) {
            return null;
        }
        Path packageDir = driverDir.resolve("package");
        String version = readClasspathVersion(loader);
        if (hasCliJs(packageDir) && version.equals(readMarker(packageDir))) {
            log.info("复用已解压的 patchright driver: {}", driverDir);
            return packageDir;
        }
        try {
            Files.createDirectories(driverDir);
        } catch (IOException e) {
            log.warn("创建 driver 解压目录失败: {}", driverDir, e);
            return null;
        }

        Path lockFile = driverDir.resolve(".extract.lock");
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                // 另一实例正在解压：等它写完，不并发写同一目录
                for (int i = 0; i < 60 && !hasCliJs(packageDir); i++) {
                    Thread.sleep(500);
                }
                return hasCliJs(packageDir) ? packageDir : null;
            }
            try {
                if (copyFromClasspath(packageDir, loader)) {
                    writeMarker(packageDir, version);
                    log.info("已从 jar 资源解压 patchright driver {}: {}", version, driverDir);
                    return packageDir;
                }
            } finally {
                lock.release();
            }
        } catch (IOException e) {
            log.warn("解压 patchright driver 失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 解压没成功但目录里已有旧版 driver：继续用，总比没有强
        return hasCliJs(packageDir) ? packageDir : null;
    }

    /** 逐个 classpath 根拷贝，拷完校验 cli.js 在才算成功 */
    private static boolean copyFromClasspath(Path packageDir, ClassLoader loader) throws IOException {
        for (URL url : Collections.list(loader.getResources(RESOURCE_PREFIX + "cli.js"))) {
            try {
                if ("jar".equals(url.getProtocol())) {
                    copyFromJar(url, packageDir);
                } else if ("file".equals(url.getProtocol())) {
                    copyFromDirectory(Path.of(url.toURI()).getParent(), packageDir);
                }
            } catch (Exception e) {
                log.debug("跳过这个 classpath 根的 driver 资源: {}", url, e);
                continue;
            }
            if (hasCliJs(packageDir)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Spring Boot fat jar 的资源 URL 形如 jar:file:/app.jar!/BOOT-INF/classes/patchright/cli.js，
     * 前缀要取到 BOOT-INF/classes/patchright/ 才能圈住这一份 driver
     * （BOOT-INF/lib 里的嵌套 jar 名字不带这个前缀，不会被误拷）。
     */
    private static void copyFromJar(URL resourceUrl, Path packageDir) throws IOException {
        JarURLConnection connection = (JarURLConnection) resourceUrl.openConnection();
        try (JarFile jar = connection.getJarFile()) {
            String entryName = connection.getEntryName();
            String base = entryName.substring(0, entryName.length() - "cli.js".length());
            for (var entries = jar.entries(); entries.hasMoreElements(); ) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!name.startsWith(base)) {
                    continue;
                }
                copyStream(jar.getInputStream(entry),
                        packageDir.resolve(name.substring(base.length())));
            }
        }
    }

    /** 开发态（IDE 直接跑、classpath 是 build/resources/main 目录）的解压来源 */
    private static void copyFromDirectory(Path patchrightDir, Path packageDir) throws IOException {
        try (Stream<Path> walk = Files.walk(patchrightDir)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                copyStream(Files.newInputStream(path),
                        packageDir.resolve(patchrightDir.relativize(path).toString()));
            }
        }
    }

    /** 先写临时文件再原子移动：中途断电不会留下半截 cli.js 被下次启动当成有效 driver */
    private static void copyStream(InputStream in, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String readClasspathVersion(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(RESOURCE_PREFIX + "package.json")) {
            if (in == null) {
                return "unknown";
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = VERSION_PATTERN.matcher(json);
            return matcher.find() ? matcher.group(1) : "unknown";
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static String readMarker(Path packageDir) {
        try {
            Path marker = packageDir.resolve(VERSION_MARKER);
            return Files.isRegularFile(marker)
                    ? Files.readString(marker, StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeMarker(Path packageDir, String version) {
        try {
            Files.writeString(packageDir.resolve(VERSION_MARKER), version, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写 driver 版本标记失败: {}", packageDir, e);
        }
    }
}
