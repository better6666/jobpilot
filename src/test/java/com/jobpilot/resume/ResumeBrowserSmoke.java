package com.jobpilot.resume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.delivery.JobCard;
import com.jobpilot.delivery.PlatformConfig;
import com.microsoft.playwright.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real Chromium + synthetic platform pages. Every request is intercepted; no HR account or public write. */
public class ResumeBrowserSmoke {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String BOSS = "https://www.zhipin.com";
    static final String LP = "https://api-c.liepin.com";
    static final String FILE = "候选人简历.pdf";
    static Browser browser;
    static Path original;
    static byte[] bytes;
    static ResumeDocument doc;
    static ResumeService storage;
    static ResumeAttachmentService sender;
    static PlatformConfig config;
    static JobCard card;
    static AtomicInteger networkSends, uploads;
    static AtomicBoolean stop;

    public static void main(String[] args) throws Exception {
        original = Files.createTempFile("jobpilot-resume-", ".pdf");
        bytes = ResumeTextExtractorTest.pdf(true); Files.write(original, bytes);
        Path root = Path.of("build/bundled-browser").toAbsolutePath();
        Path executable = root.resolve(Files.readString(root.resolve("executable.txt")).strip());
        try (Playwright playwright = Playwright.create()) {
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setExecutablePath(executable).setHeadless(true));
            reset(); config.setSendResume(false);
            check(sender.send("boss", null, config, card, () -> false) == null, "default off");
            config.setSendResume(true); config.setDryRun(true);
            check(sender.send("boss", null, config, card, () -> false).contains("预演"), "dry run avoids page and file access");
            verifyNoInteractions(storage);
            boss("disabled", false, "暂未开放", 0, 0);
            boss("request", false, "等待平台或对方确认", 1, 1);
            boss("accept", true, "平台已确认发送", 1, 0);
            boss("wrong", true, "已拦截", 0, 0);
            boss("uncertain", true, "未确认", 1, 0);
            liepin(false, false, "请先开启", 0, 0);
            liepin(true, false, "平台已确认发送", 1, 1);
            liepin(true, true, "已拦截", 0, 0);
            com.jobpilot.boss.BossVerificationSmoke.run(browser);
            browser.close();
        } finally { Files.deleteIfExists(original); }
        System.out.println("PASS resume browser: default off, dry run, BOSS upload/request/accept/guard/uncertain/dedup, Liepin consent/upload/exact attachment/guard");
    }
    static void reset() throws Exception {
        networkSends = new AtomicInteger(); uploads = new AtomicInteger(); stop = new AtomicBoolean();
        doc = new ResumeDocument(); doc.setId("qa-resume"); doc.setName(FILE); doc.setExtension("pdf");
        doc.setSize(bytes.length);
        storage = mock(ResumeService.class);
        when(storage.get("qa-resume")).thenReturn(doc);
        when(storage.attachment("qa-resume")).thenReturn(original);
        doAnswer(c -> { doc.getPlatformUploadIds().put(c.getArgument(1), c.getArgument(2)); return null; }).when(storage).rememberUpload(anyString(), anyString(), anyString());
        doAnswer(c -> { doc.getSentTo().put(c.getArgument(1), c.getArgument(2)); return null; }).when(storage).rememberSent(anyString(), anyString(), anyString());
        sender = new ResumeAttachmentService(storage, JSON);
        config = new PlatformConfig(); config.setSendResume(true); config.setResumeId(doc.getId()); config.setDryRun(false);
        card = mock(JobCard.class); when(card.getJobId()).thenReturn("qa-job"); when(card.getBossId()).thenReturn("qa-recipient");
    }
    static void boss(String mode, boolean cached, String expected, int sends, int fileUploads) throws Exception {
        System.out.println("BOSS " + mode); reset(); if (cached) doc.getPlatformUploadIds().put("boss", "selected-id");
        try (BrowserContext context = browser.newContext()) {
            context.route("**/*", route -> {
                String url = route.request().url();
                if (url.contains("/exchange/")) {
                    check(route.request().postData().contains("selected-id"), "only chosen BOSS attachment reaches network");
                    networkSends.incrementAndGet();
                    if (mode.equals("uncertain")) stop.set(true);
                    fulfill(route, "application/json", mode.equals("uncertain") ? "{}" : "{\"code\":0,\"zpData\":{\"status\":0}}");
                } else if (url.contains("attachment/save.json")) {
                    try { byte[] received = Base64.getDecoder().decode(JSON.readTree(route.request().postData()).path("bytes").asText()); check(Arrays.equals(received, bytes), "unchanged BOSS original file"); }
                    catch (Exception e) { throw new AssertionError(e); }
                    uploads.incrementAndGet(); fulfill(route, "application/json", "{\"code\":0,\"zpData\":{\"resumeId\":\"selected-id\"}}");
                } else if (url.endsWith("/web/geek/resume")) {
                    fulfill(route, "text/html", "<input type=file ka='user-resume-upload-file'><button ka='user_resume_add_sure'>确定添加</button><script>document.querySelector('button').onclick=()=>{let r=new FileReader();r.onload=()=>fetch('/wapi/zpgeek/resume/attachment/save.json',{method:'POST',body:JSON.stringify({bytes:r.result.split(',')[1]})});r.readAsDataURL(document.querySelector('input').files[0]);}</script>");
                } else {
                    String id = mode.equals("wrong") ? "other-resume" : "selected-id";
                    String endpoint = mode.equals("request") ? "request" : "accept";
                    String action = "fetch('/wapi/zpchat/exchange/" + endpoint + "',{method:'POST',body:'encryptResumeId=" + id + "'}).catch(()=>{})";
                    String html = "<div d-c='62009' class='" + (mode.equals("disabled") ? "unable" : "toolbar-btn") + "'>发简历</div>";
                    if (mode.equals("request")) html += "<div class='choose-resume-dialog' hidden><div class='resume-list'><div class='list-item'><span class='resume-name'>" + FILE + "</span></div></div><button class='btn-confirm'>发送</button></div><script>document.querySelector('[d-c]').onclick=()=>document.querySelector('.choose-resume-dialog').hidden=false;document.querySelector('button').onclick=()=>{" + action + "};</script>";
                    else html += "<script>document.querySelector('[d-c]').onclick=()=>{" + action + "};</script>";
                    fulfill(route, "text/html", html);
                }
            });
            Page page = context.newPage(); page.navigate(BOSS + "/web/geek/chat");
            String result = sender.send("boss", page, config, card, stop::get);
            check(result.contains(expected), mode + " result: " + result);
            check(networkSends.get() == sends && uploads.get() == fileUploads, mode + " side effects");
            if (sends > 0) {
                stop.set(false); String again = sender.send("boss", page, config, card, stop::get);
                check(again.contains("跳过") || again.contains("不自动重复"), "attachment/request dedup: " + again);
                check(networkSends.get() == sends, "no duplicate network write");
            }
            check(context.pages().size() == 1, "owned upload tab closed");
        }
    }
    static void liepin(boolean consent, boolean wrong, String expected, int sends, int fileUploads) throws Exception {
        System.out.println("Liepin consent=" + consent + " wrong=" + wrong); reset(); config.setLiepinResumeSync(consent);
        if (wrong) doc.getPlatformUploadIds().put("liepin", "chosen-lp");
        String list = "{\"flag\":1,\"data\":{\"list\":[{\"auditStatus\":1,\"fileId\":\"radio-17\",\"encryAttachmentId\":\"chosen-lp\",\"fileName\":\"候选人简历\",\"originalExtension\":\"pdf\"}]}}";
        try (BrowserContext context = browser.newContext()) {
            context.route("**/*", route -> {
                String url = route.request().url();
                if (url.contains("chat.send-resume")) {
                    check(route.request().postData().contains("chosen-lp"), "chosen Liepin ID only");
                    networkSends.incrementAndGet(); fulfill(route, "application/json", "{\"flag\":1}");
                } else if (url.contains("save-attachment-resume")) {
                    try { check(Arrays.equals(Base64.getDecoder().decode(JSON.readTree(route.request().postData()).path("bytes").asText()), bytes), "unchanged Liepin file"); }
                    catch (Exception e) { throw new AssertionError(e); }
                    uploads.incrementAndGet(); fulfill(route, "application/json", "{\"flag\":1}");
                } else if (url.contains("list-attachment-resume")) fulfill(route, "application/json", uploads.get() == 0 && !wrong ? "{\"flag\":1,\"data\":{\"list\":[]}}" : list);
                else if (url.contains("/resume/edit")) fulfill(route, "text/html", "<button data-testid='attachment-resume-add-btn'>上传附件</button><div class='uploadResumeModal--XzExM' hidden><input type=file></div><script>const list=()=>fetch('" + LP + ResumeAttachmentService.LIEPIN_LIST + "',{method:'POST'});list();document.querySelector('button').onclick=()=>document.querySelector('div').hidden=false;document.querySelector('input').onchange=()=>{let r=new FileReader();r.onload=async()=>{await fetch('"+LP+"/api/com.liepin.cresume.attachment.save-attachment-resume',{method:'POST',body:JSON.stringify({fileName:'候选人简历',bytes:r.result.split(',')[1]})});await list();};r.readAsDataURL(document.querySelector('input').files[0]);};</script>");
                else fulfill(route, "text/html", "<div class='im-ui-basic-chat-wrapper'><button class='action-resume'>发简历</button></div><div class='_11156ZZr1k' hidden><p>选择附件简历</p><input type=radio value=radio-17><button>立即投递</button></div><script>document.querySelector('.action-resume').onclick=async()=>{" + (wrong ? "fetch('"+LP+"/api/com.liepin.im.c.chat.send-resume',{method:'POST',body:JSON.stringify({attachmentId:'other-lp'})}).catch(()=>{});" : "await fetch('"+LP+ResumeAttachmentService.LIEPIN_LIST+"',{method:'POST'});document.querySelector('._11156ZZr1k').hidden=false;") + "};document.querySelector('._11156ZZr1k button').onclick=()=>fetch('"+LP+"/api/com.liepin.im.c.chat.send-resume',{method:'POST',body:JSON.stringify({attachmentId:document.querySelector('input').checked?'chosen-lp':'other-lp'})});</script>");
            });
            Page page = context.newPage(); page.navigate("https://c.liepin.com/qa-chat");
            String result = sender.send("liepin", page, config, card, () -> false);
            check(result.contains(expected), "Liepin result: " + result + " uploads=" + uploads + " ids=" + doc.getPlatformUploadIds());
            check(networkSends.get() == sends && uploads.get() == fileUploads, "Liepin side effects " + networkSends + "/" + uploads);
            check(context.pages().size() == 1, "Liepin owned upload tab closed");
        }
    }
    static void fulfill(Route route, String type, String body) { route.fulfill(new Route.FulfillOptions().setContentType(type + "; charset=utf-8").setHeaders(Map.of("Access-Control-Allow-Origin", "*", "Access-Control-Allow-Headers", "*", "Access-Control-Allow-Methods", "GET,POST,OPTIONS")).setBody(body)); }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
