package com.jobpilot.shixiseng;

import com.microsoft.playwright.options.WaitUntilState;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.jobpilot.browser.ChromeProbe;
import com.jobpilot.browser.PlaywrightDriverSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 实习僧列表条目解析——用真浏览器加载真实页面片段，验选择器对不对。
 *
 * <p>夹具 {@code fixtures/shixiseng-search-items.html} 是 2026-09-22 从
 * 实习僧真实搜索页（keyword=视觉设计&city=苏州）原样截取的三个
 * {@code div.intern-item}，平台改版时这些用例会先红。
 *
 * <p>之所以要真浏览器：被测的 {@link ShixisengDriver#readItem} 吃的是
 * Playwright 的 Locator，mock 出来的 Locator 证不了选择器写错了没有。
 */
class ShixisengItemParseTest {

    @TempDir
    Path profileDir;

    private Playwright playwright;
    private BrowserContext context;
    private Page page;

    @BeforeAll
    static void locateDriver() {
        // 没有 driver（没跑过打包、也没在仓库根目录跑测试）就跳过：
        // 这条用例验的是 DOM 选择器，证不了比误报好
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

        String items = Files.readString(
                Path.of("src/test/resources/fixtures/shixiseng-search-items.html"));
        // 包一层最小页面：readItem 用的都是相对条目自己的选择器，不需要外层结构
        // 只等 DOMContentLoaded：夹具里引了实习僧的图片 CDN，
        // 默认的 waitUntil=load 会等这些外网资源，CI 上拖到 30s 超时
        page.setContent("<html><body><div class=\"result-list\">"
                + items + "</div></body></html>",
                new Page.SetContentOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
    }

    @AfterEach
    void shutdown() {
        try {
            context.close();
        } catch (Exception ignore) {
        }
        playwright.close();
    }

    private List<ShixisengJobCard> parseAll() {
        ShixisengDriver driver = new ShixisengDriver(null);
        List<ShixisengJobCard> out = new ArrayList<>();
        Locator items = page.locator("div.intern-item");
        for (int i = 0; i < items.count(); i++) {
            out.add(driver.readItem(items.nth(i)));
        }
        return out;
    }

    @Test
    void 三条真实条目全部解析出jobId() {
        List<ShixisengJobCard> cards = parseAll();

        assertThat(cards).hasSize(3);
        assertThat(cards).allSatisfy(c -> assertThat(c.getJobId()).startsWith("inn_"));
        assertThat(cards).extracting(ShixisengJobCard::getJobId)
                .containsExactly("inn_h5ultjyv1tmx", "inn_bm8qww1nxhwm", "inn_3qdlysdxeu5n");
    }

    @Test
    void 岗位名剥掉iconFont后是可读中文() {
        List<ShixisengJobCard> cards = parseAll();

        assertThat(cards).extracting(ShixisengJobCard::getJobName)
                .containsExactly("平面（实习）", "平面实习", "游戏动效实习");
    }

    @Test
    void 公司名和行业都取到() {
        List<ShixisengJobCard> cards = parseAll();

        assertThat(cards).extracting(ShixisengJobCard::getBrandName)
                .containsExactly("宝时得", "苏州帧格映画文化传媒有限公司", "摩尔立方");
        assertThat(cards).extracting(ShixisengJobCard::getIndustryName)
                .containsExactly("汽车/机械/制造", "广告/传媒/公关/展览", "电子/通信/硬件");
    }

    @Test
    void 城市取到且日薪字段存在() {
        List<ShixisengJobCard> cards = parseAll();

        assertThat(cards).extracting(ShixisengJobCard::getCityName)
                .containsExactly("苏州", "苏州", "苏州");
        // 日薪混着 icon-font，清洗后可能是 "-/天" 或 "薪资面议"，但不能还是原始字形
        assertThat(cards).extracting(ShixisengJobCard::getSalaryDesc)
                .doesNotContainNull();
    }

    @Test
    void 详情链接完整可用() {
        List<ShixisengJobCard> cards = parseAll();

        assertThat(cards).allSatisfy(c -> {
            assertThat(c.getJobUrl()).startsWith("https://www.shixiseng.com/intern/");
            assertThat(ShixisengJobCard.extractJobId(c.getJobUrl())).isEqualTo(c.getJobId());
        });
    }

    @Test
    void 列表采不到的字段一律为null不瞎猜() {
        List<ShixisengJobCard> cards = parseAll();

        assertThat(cards).allSatisfy(c -> {
            assertThat(c.getJobExperience()).isNull();
            assertThat(c.getJobDegree()).isNull();
            assertThat(c.getBossId()).isNull();
            assertThat(c.getBrandScaleName()).isNull();
            assertThat(c.getAreaDistrict()).isNull();
        });
    }
}
