package com.jobpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobpilot.browser.BrowserManager;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.Cookie;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReleasePreviewSmoke {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Duration TIMEOUT = Duration.ofSeconds(40);
    private static final String BOSS_API = "/wapi/zpgeek/job/detail.json";
    private static final String LIEPIN_API = "/api/com.liepin.searchfront4c.pc-search-job";
    private static final AtomicInteger BOSS_DETAILS = new AtomicInteger();
    private static final AtomicInteger LIEPIN_RESULTS = new AtomicInteger();
    private static final AtomicInteger OFFSITE_WRITES = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        try (var app = JobPilotApplication.start(new String[]{
                "--server.address=127.0.0.1", "--server.port=0",
                "--license.enabled=false", "--jobpilot.console=false",
                "--updates.manifest-url=http://127.0.0.1:1/updates.json",
                "--browser.channel=" + System.getProperty("qa.browser.channel", "bundled"), "--browser.headless=true", "--browser.slow-mo-ms=0"
        })) {
            int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
            String origin = "http://127.0.0.1:" + port;
            check("1.0.30".equals(api(origin, "GET", "/api/health", null).path("version").asText()), "release version");
            BrowserManager manager = app.getBean(BrowserManager.class);
            manager.submit(() -> {
                var browser = manager.context();
                browser.route("**/*", route -> serve(route, port));
                browser.addCookies(List.of(
                        new Cookie("bst", "synthetic-only").setUrl("https://www.zhipin.com"),
                        new Cookie("lt_auth", "synthetic-only").setUrl("https://www.liepin.com")));
                return null;
            }, TIMEOUT);

            var boss = api(origin, "GET", "/api/boss/config", null);
            check(boss.path("requestWechat").isBoolean() && !boss.path("requestWechat").asBoolean(), "WeChat default off");
            verifyPreview(manager, origin, "boss", "BOSS release fixture");
            verifyPreview(manager, origin, "liepin", "Liepin release fixture");
            check(BOSS_DETAILS.get() == 1, "BOSS read exactly one fixture job");
            check(LIEPIN_RESULTS.get() == 1, "Liepin read exactly one fixture result");
            check(OFFSITE_WRITES.get() == 0, "preview submitted no application, message or contact request");
            System.out.println("PASS shipped JAR rendered workbench, collected jobs and saved preview records on " + System.getProperty("os.name"));
        }
    }

    private static void verifyPreview(BrowserManager manager, String origin, String platform, String name) throws Exception {
        String base = "/api/" + platform;
        ObjectNode config = (ObjectNode) api(origin, "GET", base + "/config", null);
        check(config.path("dryRun").asBoolean(), platform + " defaults to preview");
        config.putArray("keywords").add("Release preview fixture");
        config.put("aiGreeting", false);
        config.put("sayHi", "Synthetic preview only");
        config.put("maxJobsPerKeyword", 1);
        config.put("waitSeconds", 1);
        config.put("dryRun", true);
        config.put("sendResume", false);
        if (platform.equals("boss")) config.put("requestWechat", true);
        api(origin, "PUT", base + "/config", config);

        Page workbench = manager.submit(() -> {
            Page page = manager.context().newPage();
            page.navigate(origin + "/delivery.html?platform=" + platform);
            page.locator("#dryRunBanner").waitFor();
            check(page.locator("#dryRun").isChecked(), platform + " workbench preview is selected");
            var result = page.waitForResponse(
                    response -> response.url().endsWith(base + "/start") && response.request().method().equals("POST"),
                    () -> page.locator("#btnStart").click());
            check(JSON.readTree(result.text()).path("success").asBoolean(), platform + " workbench starts preview");
            return page;
        }, TIMEOUT);
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        JsonNode status;
        do {
            status = api(origin, "GET", base + "/status", null);
            if ("FINISHED".equals(status.path("state").asText())) break;
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        check("FINISHED".equals(status.path("state").asText()) && status.path("loggedIn").asBoolean()
                && status.path("scanned").asInt(-1) == 1 && status.path("previewed").asInt(-1) == 1
                && status.path("delivered").asInt(-1) == 0 && status.path("failed").asInt(-1) == 0,
                platform + " result " + status);
        JsonNode records = api(origin, "GET", base + "/deliveries", null);
        check(records.size() == 1 && "预演".equals(records.get(0).path("deliveryStatus").asText())
                && name.equals(records.get(0).path("jobName").asText()), platform + " saved the expected preview record " + records);
        manager.submit(() -> {
            workbench.close();
            return null;
        }, TIMEOUT);
        System.out.println("PASS " + platform + " workbench preview: scanned=1, previewed=1, sent=0, record saved");
    }

    private static JsonNode api(String origin, String method, String path, JsonNode body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(10));
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode result = JSON.readTree(response.body());
        check(response.statusCode() == 200 && result.path("success").asBoolean(), method + " " + path + " " + response.body());
        return result.path("data");
    }

    private static void serve(Route route, int localPort) {
        URI uri = URI.create(route.request().url());
        String host = uri.getHost();
        String path = uri.getPath();
        if ("127.0.0.1".equals(host) && uri.getPort() == localPort) {
            route.resume();
            return;
        }
        if (!"GET".equals(route.request().method())) {
            OFFSITE_WRITES.incrementAndGet();
            route.abort();
            return;
        }
        if ("www.zhipin.com".equals(host) && BOSS_API.equals(path)) {
            BOSS_DETAILS.incrementAndGet();
            fulfill(route, "application/json", """
                    {"zpData":{"jobInfo":{"encryptId":"release-fixture-boss","encryptUserId":"fixture-hr",
                    "jobName":"BOSS release fixture","postDescription":"Synthetic test job"},
                    "brandComInfo":{"brandName":"Local preview company","brandScaleName":"100-499 people"},
                    "bossInfo":{"name":"Synthetic contact"}}}
                    """);
        } else if ("www.zhipin.com".equals(host) && "/web/geek/jobs".equals(path)) {
            fulfill(route, "text/html", """
                    <ul class="rec-job-list"><li class="job-card-box" onclick="fetch('%s')">
                    <button>Synthetic listing</button></li></ul>
                    """.formatted(BOSS_API));
        } else if ("www.liepin.com".equals(host) && LIEPIN_API.equals(path)) {
            LIEPIN_RESULTS.incrementAndGet();
            fulfill(route, "application/json", """
                    {"data":{"jobCardList":[{"job":{"jobId":"731","title":"Liepin release fixture"},
                    "comp":{"compName":"Local preview company","compScale":"100-499人"}}]}}
                    """);
        } else if ("www.liepin.com".equals(host) && "/zhaopin/".equals(path)) {
            fulfill(route, "text/html", """
                    <main></main><script>
                    fetch('%s').then(r=>r.json()).then(()=>{
                      document.querySelector('main').innerHTML =
                        '<div class="jobCardPcContainer"><a href="/job/731.shtml">Synthetic listing</a><button>聊一聊</button></div>';
                    });
                    </script>
                    """.formatted(LIEPIN_API));
        } else if ("www.liepin.com".equals(host) && "/".equals(path)) {
            fulfill(route, "text/html", "<main>Synthetic Liepin homepage</main>");
        } else {
            route.abort();
        }
    }

    private static void fulfill(Route route, String type, String body) {
        route.fulfill(new Route.FulfillOptions().setContentType(type + "; charset=utf-8").setBody(body));
    }

    private static void check(boolean actual, String message) {
        if (!actual) throw new AssertionError(message);
    }
}
