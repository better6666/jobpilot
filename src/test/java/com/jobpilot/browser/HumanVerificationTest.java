package com.jobpilot.browser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    }
}
