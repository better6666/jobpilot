package com.jobpilot.job51;

import com.jobpilot.browser.BrowserManager;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 51job 的登录判据。
 *
 * <p>这条是回归钉：判据曾经是"profile 里有没有名为 {@code 51job} 的 cookie"，
 * 而 we.51job.com 登录后发的其实是不带平台名的 {@code JSESSIONID}（匿名访客也发），
 * 结果是登录了也永远判成未登录，点开始只等到超时、一个岗位都不投。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Job51LoginTest {

    @Mock
    private BrowserManager browserManager;

    @Mock
    private Page page;

    @Mock
    private Locator visible;

    @Mock
    private Locator absent;

    private Job51Driver driver() {
        return new Job51Driver(browserManager);
    }

    /** 只让列出的选择器"看得见"，其余一律查不到——等价于页面上只有那几个节点存在 */
    private void showOnly(String... shownSelectors) {
        when(visible.count()).thenReturn(1);
        when(visible.isVisible()).thenReturn(true);
        when(visible.first()).thenReturn(visible);
        when(absent.count()).thenReturn(0);
        when(absent.isVisible()).thenReturn(false);
        when(absent.first()).thenReturn(absent);
        when(page.locator(anyString())).thenReturn(absent);
        for (String selector : shownSelectors) {
            when(page.locator(selector)).thenReturn(visible);
        }
    }

    @Test
    void 导航栏出现用户名入口即判为已登录() {
        showOnly("a.uname.e_icon.at");
        assertTrue(driver().isLoggedIn(page));
    }

    @Test
    void 我的投递入口也算已登录() {
        showOnly("a[href*='/pc/my/myjob']");
        assertTrue(driver().isLoggedIn(page));
    }

    @Test
    void 通用用户信息块也算已登录() {
        showOnly(".login-info, .user-info, .username");
        assertTrue(driver().isLoggedIn(page));
    }

    @Test
    void 登录注册按钮还挂着即判为未登录() {
        showOnly("span.login.loginBtnClick");
        assertFalse(driver().isLoggedIn(page));
    }

    /** 两个方向都探不到（页面改版/还在跳转）时按未登录处理，宁可让用户重扫一次 */
    @Test
    void 什么信号都没有时按未登录处理() {
        showOnly();
        assertFalse(driver().isLoggedIn(page));
    }
}
