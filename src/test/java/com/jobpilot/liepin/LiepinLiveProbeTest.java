package com.jobpilot.liepin;

import com.jobpilot.browser.PlaywrightDriverSupport;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class LiepinLiveProbeTest {
    @Test
    void probe() throws Exception {
        PlaywrightDriverSupport.ensureDriverDir();
        try (Playwright playwright = Playwright.create()) {
            BrowserContext context = playwright.chromium().launchPersistentContext(
                    Path.of("/tmp/jobpilot-flow-test/user/Library/Application Support/JobPilot/browser-data"),
                    new BrowserType.LaunchPersistentContextOptions()
                            .setChannel("chrome").setHeadless(false).setViewportSize(1440, 900));
            try {
                Page page = context.newPage();
                page.navigate("https://www.liepin.com/zhaopin/?currentPage=0&key=%E8%A7%86%E8%A7%89%E8%AE%BE%E8%AE%A1",
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForTimeout(8000);
                Files.writeString(Path.of("/tmp/jobpilot-liepin-probe.txt"),
                        "URL: " + page.url() + "\nTitle: " + page.title() + "\nold cards: "
                                + page.locator("div[class*='job-card-pc-container']").count()
                                + "\nbody: " + page.locator("body").innerText().substring(0, Math.min(1200, page.locator("body").innerText().length()))
                                + "\nclasses: " + page.evaluate("() => [...new Set([...document.querySelectorAll('[class*=job]')].map(e => e.className).filter(x => typeof x === 'string'))].slice(0,100)")
                                + "\n");
                Files.writeString(Path.of("/tmp/jobpilot-liepin-first-card.html"),
                        String.valueOf(page.locator("div[class*='jobCardPcContainer']").first()
                                .evaluate("el => el.outerHTML")));
                page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/jobpilot-liepin-probe.png")));
            } finally {
                context.close();
            }
        }
    }
}
