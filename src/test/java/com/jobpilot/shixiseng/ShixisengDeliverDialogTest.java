package com.jobpilot.shixiseng;

import com.jobpilot.browser.BrowserManager;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 点了"投个简历"之后那两个框的判定。
 *
 * <p>回归钉来自 2026-09-26 的真实抓取：实习僧详情页常驻挂着四个
 * {@code .el-dialog}（切换城市/举报/投递/问卷），全都是 {@code display:none}。
 * 老代码 {@code page.locator(".el-dialog...").first()} 于是永远命中第一个隐藏窗，
 * 简历选择框真弹出来也判成"没弹窗"，最后记一句没用的"简历选择框没能处理"。
 *
 * <p>另一半：简历没完善时平台弹的不是 dialog 而是 message-box
 * （"友情提示 / 请完善简历后投递"），要求人到网页端补简历。这种情况必须把
 * 平台原话留在失败原因里，用户才知道下一步该自己做什么。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShixisengDeliverDialogTest {

    @Mock
    private BrowserManager browserManager;

    @Mock
    private Page page;

    @Mock
    private Locator allDialogs;

    @Mock
    private Locator hiddenCityDialog;

    @Mock
    private Locator visibleResumeDialog;

    @Mock
    private Locator noticeMessage;

    @Mock
    private Locator noMessage;

    private ShixisengDriver driver() {
        return new ShixisengDriver(browserManager);
    }

    /** 页面挂着 {@code count} 个 el-dialog，只有第 visibleAt 个是可见的 */
    private void dialogs(int count, int visibleAt) {
        when(allDialogs.count()).thenReturn(count);
        for (int i = 0; i < count; i++) {
            when(allDialogs.nth(i)).thenReturn(i == visibleAt ? visibleResumeDialog : hiddenCityDialog);
        }
        when(visibleResumeDialog.isVisible()).thenReturn(true);
        when(hiddenCityDialog.isVisible()).thenReturn(false);
        when(page.locator(".el-dialog")).thenReturn(allDialogs);
    }

    private void messageBox(boolean visible, String text) {
        when(noMessage.count()).thenReturn(0);
        when(noMessage.isVisible()).thenReturn(false);
        when(noMessage.first()).thenReturn(noMessage);
        when(noticeMessage.count()).thenReturn(1);
        when(noticeMessage.first()).thenReturn(noticeMessage);
        when(noticeMessage.isVisible()).thenReturn(visible);
        when(noticeMessage.innerText(any(Locator.InnerTextOptions.class))).thenReturn(text);
        when(page.locator(anyString())).thenReturn(noMessage);
        when(page.locator(".el-message-box__message")).thenReturn(visible ? noticeMessage : noMessage);
    }

    @Test
    void 挑弹窗要跳过隐藏的那几个命中可见的简历选择框() {
        dialogs(4, 2);

        assertSame(visibleResumeDialog, driver().visibleDialog(page));
    }

    @Test
    void 全是隐藏窗时判为没有弹窗() {
        dialogs(4, -1);

        assertNull(driver().visibleDialog(page));
    }

    @Test
    void 简历没完善时把平台原话读出来() {
        messageBox(true, "请完善简历后投递");

        assertEquals("请完善简历后投递", driver().platformNotice(page));
    }

    @Test
    void 提示框藏在DOM里不算拒投() {
        messageBox(false, "请完善简历后投递");

        assertNull(driver().platformNotice(page));
    }

    @Test
    void 没有提示框时读不出东西() {
        messageBox(false, null);
        when(page.locator(".el-message-box__message")).thenReturn(noMessage);

        assertNull(driver().platformNotice(page));
    }
}
