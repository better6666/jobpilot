package com.jobpilot.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 样本是本机库 fail_reason 列里的原文 */
class FailReasonsTest {

    @Test
    void Playwright超时堆栈翻成人话() {
        String raw = "Error {\n  message='Timeout 10000ms exceeded.\n  name='TimeoutError\n  stack='TimeoutError: Timeout 10000ms exceeded.\n"
                + "    at _ProgressController.run (/Users/x/driver/package/lib/coreBundle.js:12135:32)\n}\nCall log:\n"
                + "-   - waiting for locator(\"div#chat-input\")";
        assertEquals("页面操作超时（元素长时间没响应，可能是页面卡住或网络慢）", FailReasons.clean(raw));
    }

    @Test
    void 其他Playwright错误只留message那一行() {
        assertEquals("Element is not attached to the DOM",
                FailReasons.clean("Error {\n  message='Element is not attached to the DOM\n  name='Error\n  stack='...'}"));
    }

    @Test
    void 页面被关和网络错误() {
        assertTrue(FailReasons.clean("Target page, context or browser has been closed").startsWith("浏览器页面被关掉了"));
        assertEquals("网络错误（ERR_CONNECTION_RESET），检查网络或代理",
                FailReasons.clean("page.navigate: net::ERR_CONNECTION_RESET at https://www.zhipin.com/"));
    }

    @Test
    void 本来就是人话的原样保留_超长截断() {
        assertEquals("未找到聊天输入框", FailReasons.clean("未找到聊天输入框"));
        assertNull(FailReasons.clean(null));
        String longText = "很".repeat(200);
        assertEquals(121, FailReasons.clean(longText).length());
    }
}
