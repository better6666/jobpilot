package com.jobpilot.zhilian;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.jobpilot.browser.PlaywrightDriverSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 智联"点投递可能新开标签页"的关门器挂在哪个对象上。
 *
 * 曾经挂在 {@code context.onPage} 上，结果第二个关键词就崩：
 * 那个回调收的是上下文里所有新页面，包括 {@link ZhilianDriver#processKeyword}
 * 自己 {@code newPage()} 建出来的列表页，页面被当场关掉，
 * {@code newPage()} 回头按 guid 取对象时已经没了，抛
 * {@code PlaywrightException: Object doesn't exist: page@...}，
 * 整个投递任务直接终止（实测：第一词 20 个跑完，第二词 0 个）。
 *
 * 挂在 page 上的 {@code onPopup} 只收这个页面自己弹出来的窗口，
 * 碰不到 driver 自建的页面，多关键词才跑得下去。
 *
 * 用真实浏览器跑：这是 Playwright 事件作用域的问题，纯 mock 证不了。
 */
class ZhilianPopupScopeTest {

    @TempDir
    Path profileDir;

    private Playwright playwright;
    private BrowserContext context;

    @BeforeAll
    static void locateDriver() {
        // 没有 driver（没跑过打包、也没在仓库根目录跑测试）就跳过：
        // 这条用例验的是浏览器事件作用域，证不了比误报好
        assumeTrue(PlaywrightDriverSupport.ensureDriverDir() != null,
                "本机没有可用的 patchright driver，跳过");
    }

    @BeforeEach
    void launch() {
        playwright = Playwright.create();
        context = playwright.chromium().launchPersistentContext(profileDir,
                new BrowserType.LaunchPersistentContextOptions()
                        .setChannel("chrome")
                        .setHeadless(true)
                        .setViewportSize(1440, 900));
    }

    @AfterEach
    void shutdown() {
        try {
            context.close();
        } catch (Exception ignore) {
            // 用例本身就在验页面被关，收尾再抛会盖掉真正的断言失败
        }
        playwright.close();
    }

    /** 每个关键词开一个新页面、注册关门器、跑完关掉——照 processKeyword 的形状来 */
    private List<String> runKeywords() {
        List<String> finished = new ArrayList<>();
        for (String keyword : List.of("陈列设计", "视觉设计")) {
            Page page = context.newPage();
            // 直接调产品代码：这一行的作用域选错就是第二个关键词崩掉的原因
            ZhilianDriver.installPopupCloser(page);
            finished.add(keyword);
            page.close();
        }
        return finished;
    }

    /** 旧写法：挂 context 上，连 newPage 自建的列表页一起关 */
    private List<String> runKeywordsWithContextScopedCloser() {
        List<String> finished = new ArrayList<>();
        for (String keyword : List.of("陈列设计", "视觉设计")) {
            Page page = context.newPage();
            context.onPage(ZhilianPopupScopeTest::closeQuietly);
            finished.add(keyword);
            page.close();
        }
        return finished;
    }

    private static void closeQuietly(Page page) {
        try {
            page.close();
        } catch (Exception ignore) {
        }
    }

    @Test
    void 关门器挂page上时每个关键词都能拿到自己的列表页() {
        assertThatNoException()
                .isThrownBy(() -> assertThat(runKeywords()).containsExactly("陈列设计", "视觉设计"));
    }

    @Test
    void 关门器挂context上时第二个关键词取不回页面直接崩() {
        // 锁死旧写法的症状：这是"installPopupCloser 不许改回 context.onPage"的判据
        Exception thrown = null;
        try {
            runKeywordsWithContextScopedCloser();
        } catch (Exception e) {
            thrown = e;
        }
        assertThat(thrown)
                .as("context.onPage 会连 newPage 自建的列表页一起关掉，第二词必崩")
                .isNotNull();
    }
}
