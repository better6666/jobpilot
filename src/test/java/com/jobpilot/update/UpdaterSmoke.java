package com.jobpilot.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 对真实安装包执行失败回滚和成功升级，所有数据均位于临时目录。 */
public class UpdaterSmoke {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final boolean MAC = System.getProperty("os.name").toLowerCase().contains("mac");
    private static final String VERSION = "1.0.30";
    private static final String PREVIOUS_VERSION = "1.0.29";
    private static Path temporary;
    private static Path isolatedHome;
    private static Path target;
    private static int appPort;

    public static void main(String[] args) throws Exception {
        Path repo = Path.of("").toAbsolutePath();
        temporary = Files.createTempDirectory("jobpilot updater 测试-");
        isolatedHome = MAC ? temporary.resolve("home") : Files.createTempDirectory("jobpilot-updater-home-");
        Path goodPackage = MAC ? repo.resolve("build/dist/JobPilot-" + VERSION + ".dmg")
                : repo.resolve("JobPilot-windows.zip");
        if (!Files.isRegularFile(goodPackage)) throw new IllegalStateException("请先打包安装文件");
        Path badPackage = buildBrokenPackage(repo);
        prepareInstalledCopy(repo, goodPackage);
        AtomicReference<Path> payload = new AtomicReference<>(badPackage);
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + fixture.getAddress().getPort();
        Map<Path, UpdateManifest.Asset> assets = Map.of(badPackage, asset(origin, badPackage), goodPackage, asset(origin, goodPackage));
        fixture.createContext("/updates.json", exchange -> {
            byte[] body = JSON.writeValueAsBytes(new UpdateManifest(VERSION, "2026-11-18",
                    List.of("QA 简历导入与 AI 画像", "QA 自选简历附件"),
                    Map.of(MAC ? "macos-arm64" : "windows-x64", assets.get(payload.get()))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        fixture.createContext("/package", exchange -> {
            Path file = payload.get();
            exchange.sendResponseHeaders(200, Files.size(file));
            try (var output = exchange.getResponseBody(); var input = Files.newInputStream(file)) { input.transferTo(output); }
        });
        fixture.start();
        try (ServerSocket port = new ServerSocket(0)) { appPort = port.getLocalPort(); }
        try {
            Process original = startInstalled(origin);
            waitVersion(PREVIOUS_VERSION);
            JsonNode config = get("/api/boss/config");
            ((com.fasterxml.jackson.databind.node.ObjectNode) config).putArray("keywords").add("updater-test-data");
            request("PUT", "/api/boss/config", config);
            waitAvailable();
            request("POST", "/api/updates/install", Map.of("version", VERSION));
            waitResult("FAILED");
            waitVersion(PREVIOUS_VERSION);
            if (original.isAlive()) throw new AssertionError("未实际进入替换和回滚阶段");
            assertSavedData();
            System.out.println("PASS: failed startup restored the previous app and preserved user data");

            payload.set(goodPackage);
            request("POST", "/api/updates/check", null);
            waitAvailable();
            request("POST", "/api/updates/install", Map.of("version", VERSION));
            waitResult("DONE");
            waitVersion(VERSION);
            assertSavedData();
            JsonNode status = get("/api/updates");
            if (status.path("installedNotes").size() == 0) throw new AssertionError("更新后未返回功能说明");
            request("POST", "/api/updates/acknowledge", Map.of("version", VERSION));
            if (get("/api/updates").path("installedNotes").size() != 0) throw new AssertionError("更新说明未记录已读状态");
            System.out.println("PASS: downloaded, verified, installed and restarted the packaged app with release notes");
            if (!MAC) {
                stopInstalled();
                Path configFile = target.resolve("app/JobPilot.cfg");
                Files.writeString(configFile, Files.readString(configFile)
                        .replace("jpackage.app-version=" + VERSION, "jpackage.app-version=" + PREVIOUS_VERSION));
                startInstalled(origin, true);
                waitVersion(PREVIOUS_VERSION);
                waitAvailable();
                Files.delete(dataDir().resolve("updates/result.json"));
                request("POST", "/api/updates/install", Map.of("version", VERSION));
                waitResult("DONE");
                waitVersion(VERSION);
                assertSavedData();
                System.out.println("PASS: Windows update also restarts correctly with zero launch arguments");
            }
        } catch (Throwable failure) {
            try (var walk = Files.walk(temporary)) {
                for (Path file : walk.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".log") || path.getFileName().toString().equals("result.json"))
                        .toList()) {
                    System.out.println("Updater diagnostic: " + temporary.relativize(file));
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    lines.stream().skip(Math.max(0, lines.size() - 35)).forEach(System.out::println);
                }
            }
            throw failure;
        } finally {
            fixture.stop(0);
            stopInstalled();
            System.out.println("Updater smoke evidence: " + temporary);
        }
    }

    private static void assertSavedData() throws Exception {
        if (!get("/api/boss/config").path("keywords").get(0).asText().equals("updater-test-data")) {
            throw new AssertionError("用户配置未保留");
        }
    }

    private static UpdateManifest.Asset asset(String origin, Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return new UpdateManifest.Asset(origin + "/package", HexFormat.of().formatHex(digest.digest()), Files.size(file));
    }

    private static Path buildBrokenPackage(Path repo) throws Exception {
        // Windows 英文构建机的 javac 不接受中文命令行路径；实际安装和 APPDATA 仍使用中文空格路径。
        Path broken = Files.createTempDirectory("jobpilot-broken-update-");
        Path classes = Files.createDirectories(broken.resolve("classes"));
        Path source = broken.resolve("BrokenUpdate.java");
        Files.writeString(source, "public class BrokenUpdate { public static void main(String[] args) { System.exit(1); } }");
        run("javac", "-d", classes.toString(), source.toString());
        Path jar = broken.resolve("broken.jar");
        run("jar", "--create", "--file", jar.toString(), "--main-class", "BrokenUpdate", "-C", classes.toString(), ".");
        Path input = Files.createDirectories(broken.resolve("input"));
        Files.copy(jar, input.resolve("broken.jar"));
        Path image = broken.resolve("dist");
        run("jpackage", "--type", "app-image", "--name", "JobPilot", "--app-version", VERSION,
                "--input", input.toString(), "--main-jar", "broken.jar", "--runtime-image", repo.resolve("build/runtime").toString(),
                "--dest", image.toString());
        if (MAC) {
            run("jpackage", "--type", "dmg", "--name", "JobPilot", "--app-version", VERSION,
                    "--app-image", image.resolve("JobPilot.app").toString(), "--dest", broken.toString());
            return broken.resolve("JobPilot-" + VERSION + ".dmg");
        }
        Path zip = broken.resolve("bad.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip)); var walk = Files.walk(image)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new ZipEntry(image.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        return zip;
    }

    private static void prepareInstalledCopy(Path repo, Path goodPackage) throws Exception {
        Path installed = Files.createDirectories(temporary.resolve("installed"));
        target = installed.resolve(MAC ? "JobPilot.app" : "JobPilot");
        Path previous = repo.resolve("build/previous").resolve(MAC ? "JobPilot-" + PREVIOUS_VERSION + ".dmg" : "JobPilot-windows.zip");
        if (!Files.isRegularFile(previous)) throw new IllegalStateException("请先下载正式 v" + PREVIOUS_VERSION + " 安装包至 build/previous");
        if (MAC) {
            Path mount = Files.createDirectories(temporary.resolve("mount"));
            run("hdiutil", "attach", "-readonly", "-nobrowse", "-mountpoint", mount.toString(), previous.toString());
            try { run("ditto", mount.resolve("JobPilot.app").toString(), target.toString()); }
            finally { run("hdiutil", "detach", mount.toString()); }
        } else {
            try (var zip = new java.util.zip.ZipInputStream(Files.newInputStream(previous))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    Path destination = installed.resolve(entry.getName()).normalize();
                    if (!destination.startsWith(installed)) throw new IllegalStateException("安装包路径异常");
                    if (entry.isDirectory()) Files.createDirectories(destination);
                    else { Files.createDirectories(destination.getParent()); Files.copy(zip, destination); }
                }
            }
        }
        System.out.println("Using the real published v" + PREVIOUS_VERSION + " client as the update source");
    }

    private static Path executable(Path app) {
        return app.resolve(MAC ? "Contents/MacOS/JobPilot" : "JobPilot.exe").toAbsolutePath();
    }

    private static Path dataDir() {
        return MAC ? temporary.resolve("home/Library/Application Support/JobPilot") : temporary.resolve("roaming/JobPilot");
    }

    private static Process startInstalled(String origin) throws Exception {
        return startInstalled(origin, false);
    }

    private static Process startInstalled(String origin, boolean noArguments) throws Exception {
        ProcessBuilder process = noArguments ? new ProcessBuilder(executable(target).toString())
                : new ProcessBuilder(executable(target).toString(), "--server.port=" + appPort,
                    "--jobpilot.console=false", "--updates.manifest-url=" + origin + "/updates.json");
        Files.createDirectories(isolatedHome);
        Files.createDirectories(temporary.resolve("roaming"));
        String options = "-Duser.home=\"" + isolatedHome + "\"";
        if (noArguments) options += " -Dserver.port=" + appPort + " -Djobpilot.console=false"
                + " -Dupdates.manifest-url=" + origin + "/updates.json";
        process.environment().put("JAVA_TOOL_OPTIONS", options);
        process.environment().put("APPDATA", temporary.resolve("roaming").toString());
        return process.redirectErrorStream(true).redirectOutput(temporary.resolve("original.log").toFile()).start();
    }

    private static void stopInstalled() throws Exception {
        List<ProcessHandle> running = ProcessHandle.allProcesses().filter(process -> process.info().command()
                .map(command -> {
                    try { return Files.isSameFile(Path.of(command), executable(target)); }
                    catch (Exception ignored) { return false; }
                }).orElse(false)).toList();
        running.forEach(ProcessHandle::destroy);
        for (ProcessHandle process : running) process.onExit().get(15, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void waitVersion(String version) throws Exception {
        for (int i = 0; i < 180; i++) {
            try {
                if (version.equals(get("/api/health").path("version").asText())) return;
            } catch (Exception ignored) { }
            Thread.sleep(500);
        }
        throw new AssertionError("没有启动版本 " + version + "，日志 " + temporary);
    }

    private static void waitAvailable() throws Exception {
        for (int i = 0; i < 60; i++) {
            JsonNode status = get("/api/updates");
            if (status.path("available").asBoolean() && "IDLE".equals(status.path("state").asText())) return;
            Thread.sleep(500);
        }
        throw new AssertionError("未检测到更新，日志 " + temporary);
    }

    private static void waitResult(String expected) throws Exception {
        Path result = dataDir().resolve("updates/result.json");
        for (int i = 0; i < 480; i++) {
            if (Files.isRegularFile(result) && expected.equals(JSON.readTree(result.toFile()).path("state").asText())) return;
            Thread.sleep(500);
        }
        throw new AssertionError("更新结果未达到 " + expected + "，日志 " + temporary);
    }

    private static JsonNode get(String path) throws Exception { return request("GET", path, null); }

    private static JsonNode request(String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path)).timeout(Duration.ofSeconds(10));
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json = JSON.readTree(response.body());
        if (!json.path("success").asBoolean()) throw new IllegalStateException(json.path("message").asText());
        return json.path("data");
    }

    private static void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
        if (process.waitFor() != 0) throw new IllegalStateException("构建或准备安装包失败: " + command[0]);
    }
}
