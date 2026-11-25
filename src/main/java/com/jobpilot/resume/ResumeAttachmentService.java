package com.jobpilot.resume;

import com.jobpilot.browser.BrowserManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.delivery.JobCard;
import com.jobpilot.delivery.PlatformConfig;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Service
public class ResumeAttachmentService {
    // Verified against the official BOSS chat-new/v5580 frontend. Interception only prevents sending a different attachment.
    static final Pattern BOSS_EXCHANGE = Pattern.compile("https://www\\.zhipin\\.com/wapi/zpchat/exchange/(request|accept)(?:\\?.*)?");
    static final Pattern LIEPIN_SEND = Pattern.compile("https://[a-z0-9-]+\\.liepin\\.com/api/com\\.liepin\\.im\\.c\\.(?:chat\\.send-resume|exchangenew\\.(?:send-exchange|accept-refuse))(?:\\?.*)?");
    static final String LIEPIN_LIST = "/api/com.liepin.cresume.attachment.list-attachment-resume";
    private final ResumeService resumes;
    private final ObjectMapper mapper;

    public ResumeAttachmentService(ResumeService resumes, ObjectMapper mapper) {
        this.resumes = resumes;
        this.mapper = mapper;
    }

    public String validate(PlatformConfig config) {
        if (!config.isSendResume()) return null;
        try { resumes.attachment(config.getResumeId()); return null; }
        catch (RuntimeException error) { return error.getMessage(); }
    }

    public String send(String platform, Page page, PlatformConfig config, JobCard card, BooleanSupplier stop) {
        if (!config.isSendResume()) return null;
        if (config.isDryRun()) return "预演：未上传或发送简历附件";
        if (stop.getAsBoolean()) return "已停止，未发送简历附件";
        try {
            ResumeDocument document = resumes.get(config.getResumeId());
            resumes.attachment(document.getId());
            String recipient = card.getBossId() == null || card.getBossId().isBlank() ? card.getJobId() : card.getBossId();
            if (recipient == null || recipient.isBlank()) return "无法核对联系人，未发送简历附件";
            String conversation = conversationKey(platform, recipient);
            String previous = document.getSentTo().get(conversation);
            if (previous != null) return previous.equals("SENT") ? "本简历此前已发送给该联系人，跳过重复附件"
                    : previous.equals("REQUESTED") ? "本简历此前已提交发送申请，等待对方同意；跳过重复申请"
                    : "本简历此前已尝试发送但未获回执，请在聊天中核对；不自动重复上传";
            String state = "boss".equals(platform) ? boss(page, document, stop) : liepin(page, document, config, stop);
            if (List.of("SENT", "REQUESTED", "UNCONFIRMED").contains(state)) resumes.rememberSent(document.getId(), conversation, state);
            return switch (state) {
                case "SENT" -> "平台已确认发送简历附件：" + document.getName();
                case "REQUESTED" -> "已提交简历发送申请，等待平台或对方确认：" + document.getName();
                case "UNCONFIRMED" -> "已尝试上传简历，但发送未确认；请在聊天中核对，不自动重复上传";
                default -> state;
            };
        } catch (Exception error) {
            return "简历附件未确认发送，请检查所选文件、网站提示或附件权限";
        }
    }

    private String boss(Page page, ResumeDocument document, BooleanSupplier stop) throws Exception {
        if (!page.url().startsWith("https://www.zhipin.com/web/geek/chat")) return "未进入当前岗位聊天页，未发送简历附件";
        Locator toolbar = page.locator("[d-c='62009']");
        if (toolbar.count() != 1 || !toolbar.isVisible()) return "网站简历发送入口未就绪，未发送附件";
        String classes = toolbar.getAttribute("class");
        if (classes != null && classes.contains("unable") || "true".equals(toolbar.getAttribute("aria-disabled"))) {
            return "平台暂未开放简历发送（可能需对方回复或同意），未上传附件";
        }
        AtomicReference<String> expected = new AtomicReference<>(document.getPlatformUploadIds().getOrDefault("boss", ""));
        AtomicReference<String> receipt = new AtomicReference<>("");
        AtomicReference<Boolean> wrongFile = new AtomicReference<>(false);
        AtomicBoolean attempted = new AtomicBoolean();
        Consumer<Route> guard = route -> {
            if (!stop.getAsBoolean() && !expected.get().isBlank() && expected.get().equals(resumeId(route.request().postData()))) {
                attempted.set(true); route.fallback();
            }
            else { wrongFile.set(true); route.abort(); }
        };
        Consumer<Response> listener = response -> {
            if (!BOSS_EXCHANGE.matcher(response.url()).matches()) return;
            if (expected.get().isBlank() || !expected.get().equals(resumeId(response.request().postData()))) return;
            try {
                JsonNode data = mapper.readTree(response.text());
                if (response.status() >= 200 && response.status() < 300 && data.path("code").asInt(-1) == 0
                        && data.path("zpData").path("status").asInt(-1) == 0) {
                    receipt.set(response.url().contains("/accept") ? "SENT" : "REQUESTED");
                }
            } catch (Exception ignored) { }
        };
        page.onResponse(listener);
        try (AutoCloseable ignored = page.route(BOSS_EXCHANGE, guard)) {
            if (expected.get().isBlank()) {
                String uploadId = uploadBoss(page.context(), document, stop);
                if (uploadId == null || uploadId.isBlank()) return "网站简历上传未确认，可能需要登录、验证或处理附件上限";
                resumes.rememberUpload(document.getId(), "boss", uploadId);
                expected.set(uploadId);
            }
            if (stop.getAsBoolean()) return "已停止，未发送简历附件";
            toolbar.click(new Locator.ClickOptions().setTimeout(10000));
            long deadline = System.currentTimeMillis() + 20000;
            boolean chosen = false, confirmed = false;
            while (System.currentTimeMillis() < deadline && !stop.getAsBoolean() && !page.isClosed()) {
                if (!receipt.get().isBlank()) return receipt.get();
                if (wrongFile.get()) return "网站默认附件与所选简历不一致，已拦截发送；请在网站中手动选择当前简历";
                Locator chooser = firstVisible(page.locator(".choose-resume-dialog"));
                if (chooser != null && !chosen) {
                    if (needsExtraConsent(chooser)) return "网站需要额外授权确认，请手动处理后发送简历";
                    Locator matched = null;
                    int matches = 0;
                    Locator entries = chooser.locator(".resume-list .list-item");
                    for (int i = 0; i < entries.count(); i++) {
                        Locator entry = entries.nth(i);
                        Locator label = entry.locator(".resume-name");
                        if (label.count() == 1 && document.getName().equals(label.innerText().strip())) { matched = entry; matches++; }
                    }
                    if (matches != 1) return "网站附件名称无法唯一匹配，未自动发送；请在网站中检查当前简历";
                    matched.click(new Locator.ClickOptions().setTimeout(10000));
                    Locator send = chooser.locator("button.btn-confirm");
                    if (send.count() != 1 || !send.isEnabled()) return "平台尚未允许发送当前附件";
                    send.click(new Locator.ClickOptions().setTimeout(10000));
                    chosen = true;
                }
                Locator panel = firstVisible(page.locator(".panel-resume"));
                if (panel != null && !confirmed) {
                    if (needsExtraConsent(panel)) return "网站需要额外授权确认，请手动处理后发送简历";
                    Locator confirm = panel.locator(".btn-sure-v2");
                    if (confirm.count() == 1 && confirm.isVisible()) { confirm.click(new Locator.ClickOptions().setTimeout(10000)); confirmed = true; }
                }
                page.waitForTimeout(250);
            }
            return attempted.get() ? "UNCONFIRMED" : "简历发送未获平台确认，请在聊天中核对";
        } catch (Exception error) {
            if (attempted.get()) return receipt.get().isBlank() ? "UNCONFIRMED" : receipt.get();
            throw error;
        } finally {
            page.offResponse(listener);
        }
    }

    private String uploadBoss(BrowserContext context, ResumeDocument document, BooleanSupplier stop) throws Exception {
        Page upload = BrowserManager.newBackgroundPage(context);
        AtomicReference<String> id = new AtomicReference<>("");
        Consumer<Response> listener = response -> {
            if (!response.url().startsWith("https://www.zhipin.com/wapi/zpgeek/resume/attachment/save.json")) return;
            try {
                JsonNode body = mapper.readTree(response.text());
                if (response.status() >= 200 && response.status() < 300 && body.path("code").asInt(-1) == 0) {
                    id.set(body.path("zpData").path("resumeId").asText(""));
                }
            } catch (Exception ignored) { }
        };
        upload.onResponse(listener);
        try {
            upload.navigate("https://www.zhipin.com/web/geek/resume", new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30000));
            if (!waitFor(upload, stop, 20000, () -> firstVisible(upload.locator(".nav-up-file a")) != null
                    || upload.locator("input[ka='user-resume-upload-file']").count() == 1)) return "";
            if (upload.locator("input[ka='user-resume-upload-file']").count() == 0) {
                firstVisible(upload.locator(".nav-up-file a")).click(new Locator.ClickOptions().setTimeout(10000));
            }
            if (!waitFor(upload, stop, 15000, () -> upload.locator("input[ka='user-resume-upload-file']").count() == 1)) return "";
            upload.locator("input[ka='user-resume-upload-file']").setInputFiles(payload(document));
            if (!waitFor(upload, stop, 90000, () -> firstVisible(upload.locator("button[ka='user_resume_add_sure']")) != null)) return "";
            firstVisible(upload.locator("button[ka='user_resume_add_sure']")).click(new Locator.ClickOptions().setTimeout(10000));
            waitFor(upload, stop, 30000, () -> !id.get().isBlank());
            return id.get();
        } finally {
            upload.offResponse(listener);
            if (!upload.isClosed()) upload.close();
        }
    }

    // Official fe-im-pc/v6 attachment picker uses encrypted attachmentId, not a chat file input.
    private String liepin(Page page, ResumeDocument document, PlatformConfig config, BooleanSupplier stop) throws Exception {
        if (!page.url().startsWith("https://www.liepin.com/") && !page.url().startsWith("https://c.liepin.com/")) return "未进入猎聘聊天，未发送简历附件";
        Locator chat = firstVisible(page.locator(".im-ui-basic-chat-wrapper"));
        if (chat == null) return "猎聘聊天窗口未就绪，未发送简历附件";
        Locator button = firstVisible(chat.locator(".action-resume"));
        if (button == null || !button.isEnabled() || "true".equals(button.getAttribute("aria-disabled"))) return "猎聘暂未开放简历发送入口";
        String expected = document.getPlatformUploadIds().getOrDefault("liepin", "");
        if (expected.isBlank()) {
            if (!config.isLiepinResumeSync()) return "未上传：请先开启“允许猎聘解析附件并完善在线简历”；猎聘上传有此网站规则";
            if (document.getPlatformUploadIds().containsKey("liepin-pending")) return "猎聘此前上传尚未核对完成，请在网站附件管理中检查，未重复上传";
            if (baseName(document.getName()).length() > 20) return "猎聘附件名称限 1–20 字，请在本地重命名后重新导入";
            expected = uploadLiepin(page.context(), document, stop);
            if (expected.isBlank()) return "猎聘附件上传或审核尚未确认，请在网站附件管理中检查";
            resumes.rememberUpload(document.getId(), "liepin", expected);
        }
        String selectedId = expected;
        Map<String, String> radioIds = new LinkedHashMap<>();
        AtomicReference<String> receipt = new AtomicReference<>("");
        AtomicBoolean wrongFile = new AtomicBoolean(), attempted = new AtomicBoolean();
        Consumer<Route> guard = route -> {
            if (!stop.getAsBoolean() && selectedId.equals(parameter(route.request().postData(), "attachmentId"))) {
                attempted.set(true); route.fallback();
            } else { wrongFile.set(true); route.abort(); }
        };
        Consumer<Response> listener = response -> {
            try {
                if (response.url().contains(LIEPIN_LIST)) {
                    for (JsonNode item : attachmentList(mapper.readTree(response.text()))) {
                        if (item.path("auditStatus").asInt(-1) == 1) radioIds.put(item.path("encryAttachmentId").asText(), item.path("fileId").asText());
                    }
                }
                if (LIEPIN_SEND.matcher(response.url()).matches() && selectedId.equals(parameter(response.request().postData(), "attachmentId"))) {
                    if (response.status() >= 200 && response.status() < 300 && mapper.readTree(response.text()).path("flag").asInt(-1) == 1) {
                        receipt.set(response.url().contains("send-exchange") ? "REQUESTED" : "SENT");
                    }
                }
            } catch (Exception ignored) { }
        };
        page.onResponse(listener);
        try (AutoCloseable ignored = page.route(LIEPIN_SEND, guard)) {
            button.click(new Locator.ClickOptions().setTimeout(10000));
            long deadline = System.currentTimeMillis() + 25000;
            boolean confirmed = false;
            while (!stop.getAsBoolean() && !page.isClosed() && System.currentTimeMillis() < deadline) {
                if (!receipt.get().isBlank()) return receipt.get();
                if (wrongFile.get()) return "猎聘默认附件与所选简历不一致，已拦截发送";
                Locator consent = firstVisible(page.locator(".ant-im-modal-content, .ant-modal-content").filter(new Locator.FilterOptions().setHasText("授权")));
                if (consent != null) return "猎聘要求额外授权，请手动处理，未自动同意";
                Locator picker = firstVisible(page.locator("._11156ZZr1k"));
                if (picker != null && !confirmed) {
                    String radio = radioIds.get(selectedId);
                    if (radio == null || radio.isBlank()) return "所选猎聘附件未通过审核或已移除，未发送网站默认简历";
                    Locator inputs = picker.locator("input[type=radio]");
                    Locator match = null;
                    for (int i = 0; i < inputs.count(); i++) if (radio.equals(inputs.nth(i).getAttribute("value"))) match = inputs.nth(i);
                    if (match == null) return "猎聘附件选择器无法核对当前简历，未发送";
                    match.check(new Locator.CheckOptions().setTimeout(10000));
                    Locator submit = picker.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                            new Locator.GetByRoleOptions().setName("立即投递").setExact(true));
                    if (submit.count() != 1 || !submit.isEnabled()) return "猎聘暂不允许发送该附件";
                    submit.click(new Locator.ClickOptions().setTimeout(10000));
                    confirmed = true;
                }
                page.waitForTimeout(250);
            }
            return attempted.get() ? "UNCONFIRMED" : "猎聘未确认发送，请在聊天中核对平台提示";
        } catch (Exception error) {
            if (attempted.get()) return receipt.get().isBlank() ? "UNCONFIRMED" : receipt.get();
            throw error;
        } finally { page.offResponse(listener); }
    }

    private String uploadLiepin(BrowserContext context, ResumeDocument document, BooleanSupplier stop) throws Exception {
        Page upload = context.newPage();
        Set<String> previous = new HashSet<>();
        AtomicBoolean saved = new AtomicBoolean();
        AtomicBoolean baselineReady = new AtomicBoolean(), uploading = new AtomicBoolean();
        AtomicReference<String> id = new AtomicReference<>("");
        AtomicReference<String> candidate = new AtomicReference<>("");
        Consumer<Response> listener = response -> {
            try {
                if (response.url().contains("/api/com.liepin.cresume.attachment.save-attachment-resume")
                        && baseName(document.getName()).equals(parameter(response.request().postData(), "fileName"))
                        && mapper.readTree(response.text()).path("flag").asInt(-1) == 1) saved.set(true);
                if (response.url().contains(LIEPIN_LIST) || response.url().contains("/api/com.liepin.cresume.web-resume-detail")) {
                    for (JsonNode item : attachmentList(mapper.readTree(response.text()))) {
                        String encrypted = item.path("encryAttachmentId").asText("");
                        if (!uploading.get()) previous.add(encrypted);
                        else if (!encrypted.isBlank() && !previous.contains(encrypted)
                                && baseName(document.getName()).equals(item.path("fileName").asText())
                                && document.getExtension().equalsIgnoreCase(item.path("originalExtension").asText())) candidate.set(encrypted);
                    }
                    baselineReady.set(true);
                }
                if (saved.get() && !candidate.get().isBlank()) id.set(candidate.get());
            } catch (Exception ignored) { }
        };
        upload.onResponse(listener);
        try {
            upload.navigate("https://c.liepin.com/resume/edit", new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30000));
            if (!waitFor(upload, stop, 25000, () -> baselineReady.get() && firstVisible(upload.locator("[data-testid='attachment-resume-add-btn']")) != null)) return "";
            firstVisible(upload.locator("[data-testid='attachment-resume-add-btn']")).click(new Locator.ClickOptions().setTimeout(10000));
            Locator input = upload.locator("[class*='uploadResumeModal--'] input[type=file]");
            if (!waitFor(upload, stop, 10000, () -> input.count() == 1)) return "";
            if (stop.getAsBoolean()) return "";
            uploading.set(true);
            input.setInputFiles(payload(document));
            waitFor(upload, stop, 100000, () -> !id.get().isBlank());
            if (saved.get() && id.get().isBlank()) resumes.rememberUpload(document.getId(), "liepin-pending", "true");
            return id.get();
        } finally {
            upload.offResponse(listener);
            if (!upload.isClosed()) upload.close();
        }
    }

    private static String baseName(String name) { return name.substring(0, name.lastIndexOf('.')); }

    private static JsonNode attachmentList(JsonNode body) {
        JsonNode data = body.has("data") ? body.path("data") : body;
        return data.has("attachmentResumeList") ? data.path("attachmentResumeList") : data.path("list");
    }

    private String parameter(String data, String name) {
        if (data == null) return "";
        try {
            if (data.stripLeading().startsWith("{")) return mapper.readTree(data).path(name).asText("");
            for (String parameter : data.split("&")) {
                String[] pair = parameter.split("=", 2);
                if (pair.length == 2 && name.equals(URLDecoder.decode(pair[0], StandardCharsets.UTF_8))) return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) { }
        return "";
    }

    private FilePayload payload(ResumeDocument document) throws Exception {
        String mime = switch (document.getExtension()) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            default -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        };
        return new FilePayload(document.getName(), mime, Files.readAllBytes(resumes.attachment(document.getId())));
    }

    private static boolean needsExtraConsent(Locator dialog) {
        String text = dialog.innerText();
        return text.contains("协议") || text.contains("授权") || dialog.locator("input[type=checkbox]:not(:checked)").count() > 0;
    }

    static Locator firstVisible(Locator candidates) {
        for (int i = 0; i < candidates.count(); i++) if (candidates.nth(i).isVisible()) return candidates.nth(i);
        return null;
    }

    private static boolean waitFor(Page page, BooleanSupplier stop, long timeout, BooleanSupplier condition) {
        long end = System.currentTimeMillis() + timeout;
        while (!stop.getAsBoolean() && !page.isClosed() && System.currentTimeMillis() < end) {
            try { if (condition.getAsBoolean()) return true; } catch (PlaywrightException ignored) { }
            page.waitForTimeout(250);
        }
        return false;
    }

    String resumeId(String data) {
        if (data == null) return "";
        try {
            if (data.stripLeading().startsWith("{")) {
                JsonNode body = mapper.readTree(data);
                return body.path("encryptResumeId").asText(body.path("resumeId").asText(""));
            }
            for (String parameter : data.split("&")) {
                String[] pair = parameter.split("=", 2);
                if (pair.length == 2 && List.of("encryptResumeId", "resumeId").contains(URLDecoder.decode(pair[0], StandardCharsets.UTF_8))) {
                    return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    static String conversationKey(String platform, String id) throws Exception {
        return platform + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));
    }
}
