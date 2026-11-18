package com.jobpilot.resume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.ai.AiProperties;
import com.jobpilot.delivery.RunCoordinator;
import com.jobpilot.license.EntitlementService;
import com.jobpilot.system.SystemPaths;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

@Service
public class ResumeService {
    public record Item(String id, String name, String extension, long size, String importedAt, boolean analyzed) { }
    public record Catalog(List<Item> items, int limit) { }
    private final Path root;
    private final ObjectMapper mapper;
    private final ResumeTextExtractor extractor;
    private final ResumeAnalyzer analyzer;
    private final AiProperties aiProperties;
    private final EntitlementService entitlements;
    private final RunCoordinator coordinator;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "jobpilot-resume-analysis");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public ResumeService(ObjectMapper mapper, ResumeTextExtractor extractor, ResumeAnalyzer analyzer,
                         AiProperties aiProperties, EntitlementService entitlements, RunCoordinator coordinator) {
        this(mapper, extractor, analyzer, aiProperties, entitlements, coordinator, SystemPaths.dataDir().resolve("resumes"));
    }

    ResumeService(ObjectMapper mapper, ResumeTextExtractor extractor, ResumeAnalyzer analyzer,
                  AiProperties aiProperties, EntitlementService entitlements, RunCoordinator coordinator, Path root) {
        this.mapper = mapper;
        this.extractor = extractor;
        this.analyzer = analyzer;
        this.aiProperties = aiProperties;
        this.entitlements = entitlements;
        this.coordinator = coordinator;
        this.root = root.toAbsolutePath().normalize();
    }

    public synchronized Catalog list() {
        List<Item> items = new ArrayList<>();
        try {
            if (Files.isDirectory(root)) try (var children = Files.list(root)) {
                for (Path child : children.filter(Files::isDirectory).toList()) {
                    if (!validId(child.getFileName().toString())) continue;
                    ResumeDocument document = read(child.getFileName().toString());
                    items.add(new Item(document.getId(), document.getName(), document.getExtension(), document.getSize(),
                            document.getImportedAt(), document.getProfile() != null));
                }
            }
        } catch (Exception error) { throw new IllegalStateException("读取本地简历失败，请检查数据目录"); }
        items.sort(Comparator.comparing(Item::importedAt).reversed());
        return new Catalog(items, limit());
    }

    public synchronized ResumeDocument importFile(String originalName, byte[] data, String replaceId) {
        boolean replace = replaceId != null && !replaceId.isBlank();
        boolean locked = false;
        Path pending = null;
        try {
            if (replace) {
                read(replaceId);
                lock("简历管理");
                locked = true;
            }
            Catalog catalog = list();
            if (!replace && catalog.limit() > 0 && catalog.items().size() >= catalog.limit()) {
                throw new IllegalArgumentException("当前最多可保存 " + catalog.limit() + " 份简历，可使用“替换所选简历”或先删除旧副本");
            }
            String name = safeName(originalName);
            String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            ResumeTextExtractor.Text parsed = extractor.extract(extension, data);
            privateRoot();
            String id = UUID.randomUUID().toString();
            pending = Files.createTempDirectory(root, ".import-");
            Files.write(pending.resolve("original." + extension), data);
            ResumeDocument document = new ResumeDocument();
            document.setId(id);
            document.setName(name);
            document.setExtension(extension);
            document.setSize(data.length);
            document.setSha256(digest(data));
            document.setImportedAt(Instant.now().toString());
            document.setText(parsed.text());
            document.setWarning(parsed.warning());
            mapper.writeValue(pending.resolve("resume.json").toFile(), document);
            Files.move(pending, directory(id), StandardCopyOption.ATOMIC_MOVE);
            pending = null;
            if (replace) removeFiles(replaceId);
            return document;
        } catch (IllegalArgumentException | IllegalStateException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("简历导入失败，请检查文件和数据目录权限"); }
        finally {
            if (pending != null) deleteTree(pending);
            if (locked) coordinator.release("简历管理");
        }
    }

    public synchronized ResumeDocument get(String id) {
        ResumeDocument document = read(id);
        if ("RUNNING".equals(document.getAnalysisState()) && !running.contains(id)) {
            document.setAnalysisState("IDLE");
            document.setAnalysisError("上次分析已中断，可以重新分析");
            save(document);
        }
        return document;
    }

    public synchronized ResumeDocument updateText(String id, String text) {
        ResumeDocument document = read(id);
        if (running.contains(id)) throw new IllegalStateException("分析进行中，请稍后修改文本");
        if (text == null || text.length() > ResumeTextExtractor.MAX_TEXT) throw new IllegalArgumentException("分析文本最多 20000 字");
        document.setText(ResumeTextExtractor.normalize(text));
        document.setWarning("");
        document.setProfile(null);
        document.setAnalyzedAt(null);
        document.setAnalysisState("IDLE");
        document.setAnalysisError("");
        save(document);
        return document;
    }

    public synchronized void delete(String id) {
        read(id);
        lock("简历管理");
        try { removeFiles(id); }
        finally { coordinator.release("简历管理"); }
    }

    public synchronized ResumeDocument analyze(String id, String targetRole) {
        ResumeDocument document = read(id);
        if (running.contains(id)) return document;
        if (document.getText().strip().length() < 30) throw new IllegalArgumentException("请先补充至少 30 字的简历文本");
        if (targetRole != null && targetRole.length() > 200) throw new IllegalArgumentException("目标岗位最多 200 字");
        if (!entitlements.allows("ai_match")) throw new IllegalStateException("当前授权暂不支持 AI 分析，请在卡密管理查看状态");
        if (entitlements.remainingToday("ai_analysis", "max_daily_ai_analysis") <= 0) {
            throw new IllegalStateException("今日 AI 分析额度已用完");
        }
        lock("简历分析");
        running.add(id);
        document.setAnalysisState("RUNNING");
        document.setAnalysisError("");
        document.setProfile(null);
        document.setAnalyzedAt(null);
        document.setTargetRole(targetRole == null ? "" : targetRole.strip());
        try {
            save(document);
            worker.submit(() -> runAnalysis(document));
            return document;
        } catch (RuntimeException error) {
            running.remove(id);
            coordinator.release("简历分析");
            throw error;
        }
    }

    private void runAnalysis(ResumeDocument snapshot) {
        try {
            ResumeProfile profile = analyzer.analyze(snapshot.getText(), snapshot.getTargetRole());
            synchronized (this) {
                ResumeDocument current = read(snapshot.getId());
                if (!snapshot.getText().equals(current.getText())) throw new IllegalStateException("简历文本已修改，请重新分析");
                current.setProfile(profile);
                current.setAnalysisState("DONE");
                current.setAnalysisError("");
                current.setAnalyzedAt(Instant.now().toString());
                save(current);
                entitlements.recordUse("ai_analysis");
            }
        } catch (Exception error) {
            synchronized (this) {
                ResumeDocument current = read(snapshot.getId());
                current.setAnalysisState("FAILED");
                current.setAnalysisError(error.getMessage() == null ? "分析失败，请重试" : error.getMessage());
                save(current);
            }
        } finally {
            running.remove(snapshot.getId());
            coordinator.release("简历分析");
        }
    }

    public synchronized void applyPersona(String id) {
        ResumeDocument document = read(id);
        if (document.getProfile() == null) throw new IllegalArgumentException("请先完成 AI 分析并确认画像");
        aiProperties.updatePersona(document.getProfile().persona());
    }

    public synchronized Path attachment(String id) {
        ResumeDocument document = read(id);
        if (!List.of("pdf", "doc", "docx").contains(document.getExtension())) {
            throw new IllegalArgumentException("聊天附件请选择 PDF 或 Word 简历；TXT 可用于画像分析");
        }
        Path path = directory(id).resolve("original." + document.getExtension());
        try {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path) || !digest(Files.readAllBytes(path)).equals(document.getSha256())) {
                throw new IllegalArgumentException("所选简历已变更或丢失，请重新导入并选择");
            }
        } catch (java.io.IOException error) { throw new IllegalArgumentException("所选简历文件无法读取，请重新导入"); }
        return path;
    }

    public synchronized void rememberUpload(String id, String platform, String uploadId) {
        ResumeDocument document = read(id);
        document.getPlatformUploadIds().put(platform, uploadId);
        save(document);
    }

    public synchronized void rememberSent(String id, String conversation, String state) {
        ResumeDocument document = read(id);
        document.getSentTo().put(conversation, state);
        save(document);
    }

    private int limit() {
        return entitlements.allows("multi_resume") ? Math.max(0, entitlements.current().quota("max_resume_count")) : 1;
    }

    private void lock(String purpose) {
        String busy = coordinator.acquire(purpose);
        if (busy != null) throw new IllegalStateException("“" + busy + "”正在运行，请先停止或等待完成");
    }

    private ResumeDocument read(String id) {
        try {
            Path directory = directory(id);
            if (Files.isSymbolicLink(directory)) throw new IllegalArgumentException("简历目录无效");
            ResumeDocument document = mapper.readValue(directory.resolve("resume.json").toFile(), ResumeDocument.class);
            if (!id.equals(document.getId())) throw new IllegalArgumentException("简历标识无效");
            return document;
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("所选简历不存在，请先导入或重新选择"); }
    }

    private void save(ResumeDocument document) {
        Path pending = null;
        try {
            Path directory = directory(document.getId());
            pending = Files.createTempFile(directory, ".metadata-", ".json");
            mapper.writeValue(pending.toFile(), document);
            Files.move(pending, directory.resolve("resume.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) { throw new IllegalStateException("保存简历信息失败"); }
        finally { if (pending != null) try { Files.deleteIfExists(pending); } catch (Exception ignored) { } }
    }

    private Path directory(String id) {
        if (!validId(id)) throw new IllegalArgumentException("简历标识无效");
        return root.resolve(id);
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }

    private void privateRoot() throws java.io.IOException {
        Files.createDirectories(root);
        try { Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException ignored) { }
    }

    private void removeFiles(String id) {
        Path directory = directory(id);
        if (!deleteTree(directory)) throw new IllegalStateException("简历文件正被占用，请关闭预览后重试");
    }

    private static boolean deleteTree(Path directory) {
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            return true;
        } catch (Exception error) { return false; }
    }

    static String safeName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("请选择简历文件");
        String clean = name.replace('\\', '/');
        clean = clean.substring(clean.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}:*?\"<>|]", "_").strip();
        int dot = clean.lastIndexOf('.');
        if (dot < 1 || !List.of("pdf", "doc", "docx", "txt").contains(clean.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("请选择 PDF、DOC、DOCX 或 TXT 简历");
        }
        return clean.length() <= 120 ? clean : clean.substring(0, 100) + clean.substring(dot);
    }

    private static String digest(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    @PreDestroy public void shutdown() { worker.shutdownNow(); }
}
