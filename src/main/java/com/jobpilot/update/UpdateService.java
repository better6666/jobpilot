package com.jobpilot.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.delivery.RunCoordinator;
import com.jobpilot.system.SystemPaths;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class UpdateService {
    public record Status(String currentVersion, String latestVersion, List<String> notes,
                         boolean available, boolean installable, String state, int progress, String message, List<String> installedNotes) {
    }

    private final ObjectMapper mapper;
    private final RunCoordinator coordinator;
    private final ApplicationArguments arguments;
    private final ServletWebServerApplicationContext context;
    private final URI manifestUri;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "jobpilot-updates");
        thread.setDaemon(true);
        return thread;
    });
    private final String currentVersion = System.getProperty("jpackage.app-version", "开发版本");
    private UpdateManifest release;
    private String state = "IDLE";
    private int progress;
    private String message = "";
    private Instant checkedAt = Instant.EPOCH;

    public UpdateService(ObjectMapper mapper, RunCoordinator coordinator, ApplicationArguments arguments,
                         ServletWebServerApplicationContext context,
                         @Value("${updates.manifest-url:https://getjobs.dpdns.org/updates.json}") URI manifestUri) {
        this.mapper = mapper;
        this.coordinator = coordinator;
        this.arguments = arguments;
        this.context = context;
        this.manifestUri = manifestUri;
    }

    public synchronized Status status() {
        if (Duration.between(checkedAt, Instant.now()).toMinutes() >= 30 && !busy()) {
            checkedAt = Instant.now();
            state = "CHECKING";
            worker.submit(this::check);
        }
        return view();
    }

    public synchronized Status refresh() {
        if (!busy() && !"CHECKING".equals(state)) {
            checkedAt = Instant.now();
            state = "CHECKING";
            worker.submit(this::check);
        }
        return view();
    }

    public synchronized Status install(String version) {
        if (busy()) return view();
        if ("CHECKING".equals(state)) throw new IllegalStateException("正在检查更新，请稍后再试");
        if (release == null || !release.version().equals(version) || !UpdateManifest.newer(version, currentVersion)) {
            throw new IllegalStateException("请先检查更新并确认最新版本");
        }
        if (applicationRoot() == null || asset() == null) {
            throw new IllegalStateException("当前运行方式不支持自动安装，请使用正式安装包");
        }
        if (coordinator.acquire("软件更新") != null) {
            throw new IllegalStateException("请先等待当前任务完成，或停止投递后，再更新并重启软件");
        }
        state = "DOWNLOADING";
        progress = 0;
        message = "正在下载安装包";
        UpdateManifest selected = release;
        worker.submit(() -> performInstall(selected));
        return view();
    }

    private void check() {
        try {
            HttpRequest request = HttpRequest.newBuilder(manifestUri).timeout(Duration.ofSeconds(12)).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            UpdateManifest found;
            try (InputStream input = response.body()) {
                if (response.statusCode() != 200) throw new IllegalStateException("更新服务暂时不可用");
                byte[] data = input.readNBytes(262145);
                if (data.length > 262144) throw new IllegalStateException("更新说明过长");
                found = mapper.readValue(data, UpdateManifest.class);
            }
            found.validate(manifestUri);
            synchronized (this) {
                release = found;
                state = "IDLE";
                message = UpdateManifest.newer(found.version(), currentVersion) ? "发现新版本" : "当前已是最新版本";
            }
        } catch (Exception error) {
            log.info("检查更新暂未完成: {}", error.getMessage());
            synchronized (this) {
                state = "IDLE";
                message = "暂时无法检查更新，请稍后重试";
            }
        }
    }

    private synchronized Status view() {
        boolean available = release != null && UpdateManifest.newer(release.version(), currentVersion);
        return new Status(currentVersion, release == null ? "" : release.version(),
                release == null ? List.of() : release.notes(), available,
                applicationRoot() != null && asset() != null, state, progress, message, installedNotes());
    }

    private List<String> installedNotes() {
        try {
            Path updates = SystemPaths.dataDir().resolve("updates");
            Path shown = updates.resolve("last-notes-shown.txt");
            if (Files.isRegularFile(shown) && currentVersion.equals(Files.readString(shown))) return List.of();
            if (release != null && currentVersion.equals(release.version())) return release.notes();
            Path result = updates.resolve("result.json");
            if (!Files.isRegularFile(result)) return List.of();
            JsonNode status = mapper.readTree(Files.readString(result));
            if (!"DONE".equals(status.path("state").asText())
                    || !currentVersion.equals(status.path("version").asText())) return List.of();
            UpdateManifest installed = mapper.readValue(updates.resolve("latest-plan.json").toFile(), UpdateManifest.class);
            return currentVersion.equals(installed.version()) ? installed.notes() : List.of();
        } catch (Exception error) {
            return List.of();
        }
    }

    public synchronized void acknowledge(String version) {
        if (!currentVersion.equals(version)) return;
        try {
            Path updates = SystemPaths.dataDir().resolve("updates");
            Files.createDirectories(updates);
            Files.writeString(updates.resolve("last-notes-shown.txt"), currentVersion);
        } catch (Exception error) {
            log.info("记录更新说明阅读状态失败: {}", error.getMessage());
        }
    }

    private boolean busy() {
        return List.of("DOWNLOADING", "PREPARING", "RESTARTING").contains(state);
    }

    private UpdateManifest.Asset asset() {
        return release == null ? null : release.assets().get(platformKey());
    }

    static String platformKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows-x64";
        if (os.contains("mac") && List.of("aarch64", "arm64").contains(System.getProperty("os.arch"))) {
            return "macos-arm64";
        }
        return "unsupported";
    }

    static Path applicationRoot() {
        Path app = new ApplicationHome(UpdateService.class).getDir().toPath().toAbsolutePath();
        if ("macos-arm64".equals(platformKey()) && Files.isDirectory(app.resolveSibling("MacOS"))) {
            return app.getParent().getParent();
        }
        if ("windows-x64".equals(platformKey()) && app.getParent() != null
                && Files.isRegularFile(app.getParent().resolve("JobPilot.exe"))) {
            return app.getParent();
        }
        return null;
    }

    private void performInstall(UpdateManifest selected) {
        Path transaction = null;
        Process installer = null;
        try {
            Path updates = SystemPaths.dataDir().resolve("updates");
            Files.createDirectories(updates);
            transaction = Files.createTempDirectory(updates, "install-");
            boolean mac = "macos-arm64".equals(platformKey());
            Path payload = transaction.resolve(mac ? "package.dmg" : "package.zip");
            UpdateManifest.Asset selectedAsset = selected.assets().get(platformKey());
            download(selectedAsset, payload);
            synchronized (this) { state = "PREPARING"; message = "正在准备安装，完成后软件会重启"; }
            String scriptName = mac ? "install-macos.sh" : "install-windows.ps1";
            Path script = transaction.resolve(scriptName);
            try (InputStream input = new ClassPathResource("updates/" + scriptName).getInputStream()) {
                Files.copy(input, script);
            }
            Files.writeString(transaction.resolve("args.json"), mapper.writeValueAsString(arguments.getSourceArgs()));
            try (var output = Files.newOutputStream(transaction.resolve("args.bin"))) {
                for (String argument : arguments.getSourceArgs()) {
                    output.write(argument.getBytes(StandardCharsets.UTF_8));
                    output.write(0);
                }
            }
            Files.writeString(updates.resolve("latest-plan.json"), mapper.writeValueAsString(selected));
            ProcessBuilder helper = mac ? new ProcessBuilder("/bin/bash", script.toString())
                    : new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "& ([scriptblock]::Create([IO.File]::ReadAllText($env:JOBPILOT_UPDATE_SCRIPT)))");
            var environment = helper.environment();
            environment.put("JOBPILOT_UPDATE_SCRIPT", script.toString());
            environment.put("JOBPILOT_UPDATE_DIR", transaction.toString());
            environment.put("JOBPILOT_UPDATE_TARGET", applicationRoot().toString());
            environment.put("JOBPILOT_UPDATE_VERSION", selected.version());
            environment.put("JOBPILOT_UPDATE_PID", Long.toString(ProcessHandle.current().pid()));
            environment.put("JOBPILOT_UPDATE_PORT", Integer.toString(context.getWebServer().getPort()));
            helper.directory(transaction.toFile()).redirectErrorStream(true)
                    .redirectOutput(transaction.resolve("installer.log").toFile());
            installer = helper.start();
            Path statusFile = transaction.resolve("status.json");
            long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
            while (System.nanoTime() < deadline) {
                if (Files.isRegularFile(statusFile)) {
                    JsonNode status = mapper.readTree(Files.readString(statusFile));
                    if ("READY".equals(status.path("state").asText())) {
                        synchronized (this) { state = "RESTARTING"; message = "准备完成，正在更新并重启"; }
                        Thread.sleep(1500);
                        try { context.close(); } finally { System.exit(0); }
                        return;
                    }
                    if ("FAILED".equals(status.path("state").asText())) {
                        throw new IllegalStateException(status.path("message").asText("准备安装失败"));
                    }
                }
                if (!installer.isAlive()) throw new IllegalStateException("安装准备未完成，请稍后重试");
                Thread.sleep(300);
            }
            throw new IllegalStateException("安装准备超时，原程序仍可使用");
        } catch (Exception error) {
            if (installer != null && installer.isAlive()) installer.destroy();
            log.error("软件更新失败，安装目录 {}", transaction, error);
            synchronized (this) { state = "FAILED"; message = error.getMessage(); }
            coordinator.release("软件更新");
        }
    }

    void download(UpdateManifest.Asset asset, Path target) throws Exception {
        var connection = (java.net.HttpURLConnection) URI.create(asset.url()).toURL().openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(false);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long length = 0;
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream input = connection.getInputStream(); var output = Files.newOutputStream(partial)) {
            if (connection.getResponseCode() != 200) throw new IllegalStateException("安装包下载失败，请重试");
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                length += count;
                if (length > asset.size()) throw new IllegalStateException("安装包长度不符");
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
                synchronized (this) { progress = (int) (length * 100 / asset.size()); }
            }
        } catch (Exception error) {
            Files.deleteIfExists(partial);
            throw error;
        } finally {
            connection.disconnect();
        }
        if (length != asset.size() || !HexFormat.of().formatHex(digest.digest()).equals(asset.sha256())) {
            Files.deleteIfExists(partial);
            throw new IllegalStateException("安装包校验失败，已保留当前程序，请重新下载");
        }
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdown();
    }
}
