package com.jobpilot.boss;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 点"立即沟通"后弹窗文字的判定 */
class BossLimitNoticeTest {

    @Test
    void 各种今日上限说法都认() {
        assertTrue(BossDriver.isLimitNotice("今日沟通人数已达上限，请明天再试"));
        assertTrue(BossDriver.isLimitNotice("您今天已与150位BOSS沟通，休息一下明天再来吧"));
        assertTrue(BossDriver.isLimitNotice("今天的沟通次数已达上限"));
    }

    @Test
    void 普通弹窗不误判() {
        assertFalse(BossDriver.isLimitNotice("已向BOSS发送消息 留在此页 继续沟通"));
        assertFalse(BossDriver.isLimitNotice("提成无上限，薪资面议"));
        assertFalse(BossDriver.isLimitNotice(null));
    }

    @Test
    void 输入超时按字数放宽() {
        assertTrue(BossDriver.typingTimeoutMs("字".repeat(120)) > 120 * 60 + 10_000,
                "120 字的话术逐字输入要 7 秒多，超时必须留够余量");
        assertEquals(15_000, BossDriver.typingTimeoutMs(null));
    }
}
