package com.jobpilot.browser;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 验证页判定。样本是 2026-10-14 在拉勾 / 58 / 应届生上实际撞到的页面 */
class HumanVerificationTest {

    @Test
    void 拉勾和应届生的阿里滑块按标题认() {
        assertTrue(HumanVerification.looksLikeVerification(
                "https://www.lagou.com/wn/zhaopin?kd=x", "滑动验证页面",
                "访问验证\n\n别离开，为了更好的访问体验，请进行验证\n\n请按住滑块，拖动到最右边"));
    }

    @Test
    void 五八的验证码页按地址认() {
        assertTrue(HumanVerification.looksLikeVerification(
                "https://callback.58.com/antibot/verifycode?serialId=abc&code=100",
                "请输入验证码 ws:1.2.3.4", "访问过于频繁，本次访问做以下验证码校验。"));
    }

    @Test
    void 标题地址都正常但正文是滑块也算() {
        assertTrue(HumanVerification.looksLikeVerification(
                "https://example.com/list", "", "请按住滑块，拖动到最右边"));
    }

    @Test
    void 正常搜索页不误判() {
        assertFalse(HumanVerification.looksLikeVerification(
                "https://www.nowcoder.com/search/job?query=%E8%BF%90%E8%90%A5&type=job",
                "运营-搜索结果-牛客网", "首页\n题库\n面试\n简历\n求职\n校招 | 产品运营"));
        assertFalse(HumanVerification.looksLikeVerification(null, null, null));
        assertFalse(HumanVerification.looksLikeVerification("https://www.zhipin.com/web/geek/jobs?query=captcha", "招聘", "岗位列表"));
    }

    @Test
    void 同页弹窗转场时保持等待并在冷却后继续() {
        var page = mock(com.microsoft.playwright.Page.class);
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/chat");
        List<?> challenge = List.of("BOSS", "岗位列表", List.of("账号异常，请完成安全验证"), List.of());
        List<?> clear = List.of("BOSS", "岗位列表", List.of(), List.of());
        when(page.evaluate(anyString())).thenReturn(challenge, clear).thenThrow(new RuntimeException("页面正在跳转"))
                .thenReturn(clear);
        var events = new ArrayList<String>();
        assertTrue(HumanVerification.waitForUser(() -> List.of(page), "BOSS", events::add, () -> false, 0, 2));
        verify(page, times(6)).waitForTimeout(1000);
        verify(page, never()).bringToFront();
        assertTrue(events.getFirst().contains("等待手动验证"));
        assertTrue(events.getLast().contains("继续处理"));
    }

    @Test
    void 仅主动请求时打开失败后的验证页() {
        var page = mock(com.microsoft.playwright.Page.class);
        var listener = mock(com.jobpilot.delivery.ProgressListener.class);
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        when(page.evaluate(anyString())).thenReturn(List.of("BOSS", "岗位列表", List.of("验证失败，请重试"), List.of()),
                List.of("BOSS", "岗位列表", List.of(), List.of()));
        when(listener.takeBrowserRequest()).thenReturn(true, false);
        assertTrue(HumanVerification.waitForUser(() -> List.of(page), "BOSS", listener, () -> false, 0, 0));
        verify(listener).waitingForBrowser(true);
        verify(page).bringToFront();
        verify(listener).waitingForBrowser(false);
    }

    @Test
    void 活跃验证可以手动停止且账号限制有单独状态() {
        var page = mock(com.microsoft.playwright.Page.class);
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        when(page.evaluate(anyString())).thenReturn(List.of("BOSS", "岗位列表", List.of(), List.of("https://www.zhipin.com/captcha")));
        AtomicInteger polls = new AtomicInteger();
        assertTrue(assertThrows(HumanVerification.PausedRun.class,
                () -> HumanVerification.waitForUser(() -> List.of(page), "BOSS", m -> {}, () -> polls.getAndIncrement() == 4))
                .getMessage().contains("已停止"));
        verify(page, never()).close();
        when(page.evaluate(anyString())).thenReturn(List.of("BOSS", "岗位列表", List.of("账号异常，操作受限"), List.of()));
        assertEquals(HumanVerification.State.RESTRICTED, HumanVerification.inspect(page));
        assertTrue(assertThrows(HumanVerification.PausedRun.class,
                () -> HumanVerification.waitForUser(() -> List.of(page), "BOSS", m -> {}, () -> false))
                .getMessage().contains("账号异常"));
    }
}
