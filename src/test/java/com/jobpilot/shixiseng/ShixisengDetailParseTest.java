package com.jobpilot.shixiseng;

import com.jobpilot.browser.ChromeProbe;
import com.jobpilot.browser.PlaywrightDriverSupport;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 实习僧详情页补字段——用真浏览器加载真实页面片段，验选择器对不对。
 *
 * <p>夹具 {@code fixtures/shixiseng-detail.html} 是 2026-09-22 从实习僧真实
 * 详情页（inn_prnx75qtfxwj，星逻智能-视觉传达实习生）原样截取的，含
 * {@code .job-header} / {@code .job_msg} / {@code .resume_apply} 整块。
 *
 * <p>之所以要真浏览器：{@link ShixisengDriver#readDetailFields} 吃的是
 * Playwright 的 Locator，mock 出来的 Locator 证不了选择器写错了没有。
 */
class ShixisengDetailParseTest {

    @TempDir
    Path profileDir;

    private Playwright playwright;
    private BrowserContext context;
    private Page page;

    @BeforeAll
    static void locateDriver() {
        assumeTrue(ChromeProbe.available(), "本机没有 Chrome，跳过浏览器用例");
        assumeTrue(PlaywrightDriverSupport.ensureDriverDir() != null,
                "本机没有可用的 patchright driver，跳过");
    }

    @BeforeEach
    void launch() throws Exception {
        playwright = Playwright.create();
        context = playwright.chromium().launchPersistentContext(profileDir,
                new BrowserType.LaunchPersistentContextOptions()
                        .setChannel("chrome")
                        .setHeadless(true)
                        .setViewportSize(1440, 900));
        page = context.newPage();
        String detail = Files.readString(
                Path.of("src/test/resources/fixtures/shixiseng-detail.html"));
        page.setContent("<html><body><div class=\"job-detail-page\">"
                + detail + "</div></body></html>");
    }

    @AfterEach
    void shutdown() {
        try {
            context.close();
        } catch (Exception ignore) {
        }
        playwright.close();
    }

    @Test
    void 补全学历每周几天和实习月数() {
        ShixisengJobCard card = new ShixisengJobCard();
        new ShixisengDriver(null).readDetailFields(page, card);

        assertThat(card.getJobDegree()).isEqualTo("本科");
        assertThat(card.getWeekDays()).isEqualTo("5天／周");
        assertThat(card.getMonthDuration()).isEqualTo("实习4个月");
    }

    @Test
    void 薪资取详情页的不用列表页那个被图标啃坏的() {
        ShixisengJobCard card = new ShixisengJobCard();
        // 列表页读到的常是 "-/天"（日薪被 icon-font 啃空了）
        card.setSalaryDesc("-/天");

        new ShixisengDriver(null).readDetailFields(page, card);

        assertThat(card.getSalaryDesc()).isEqualTo("120-180/天");
    }

    @Test
    void JD取到且非空() {
        ShixisengJobCard card = new ShixisengJobCard();

        new ShixisengDriver(null).readDetailFields(page, card);

        assertThat(card.getPostDescription()).isNotBlank();
        // JD 里应是任职要求的正文，不是导航文本
        assertThat(card.getPostDescription()).contains("在校学生");
    }

    @Test
    void 投递入口在详情页里能找到() {
        assertThat(page.locator(".resume_apply").count()).isGreaterThan(0);
        assertThat(page.locator(".resume_apply").first().innerText().trim()).isEqualTo("投个简历");
    }

    @Test
    void 详情页的岗位名和城市也能读到() {
        // 这两个字段列表页已有，详情页可作交叉校验
        assertThat(page.locator(".new_job_name span").first().innerText().trim())
                .isEqualTo("视觉传达实习生");
        assertThat(page.locator(".job_msg .job_position").first().innerText().trim())
                .isEqualTo("苏州");
    }
}
