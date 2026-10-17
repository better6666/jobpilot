package com.jobpilot.zhilian;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 码义见 ZhilianEduLevel（2026-10-17 真站逐档实测） */
class ZhilianEduLevelTest {

    @Test
    void 大专及以下按同档的码筛() {
        assertEquals("5", ZhilianEduLevel.code("大专"));
        assertEquals("5", ZhilianEduLevel.code("专科"));
        assertEquals("12", ZhilianEduLevel.code("中专/中技"));
        assertEquals("12", ZhilianEduLevel.code("高中/中专"));
        assertEquals("7", ZhilianEduLevel.code("高中"));
        assertEquals("9", ZhilianEduLevel.code("初中及以下"));
    }

    @Test
    void 本科及以上和没填的不在搜索里筛() {
        assertNull(ZhilianEduLevel.code("本科"));
        assertNull(ZhilianEduLevel.code("硕士"));
        assertNull(ZhilianEduLevel.code(""));
        assertNull(ZhilianEduLevel.code(null));
    }

    @Test
    void 拼进搜索地址() {
        ZhilianProperties.ZhilianConfig config = new ZhilianProperties.ZhilianConfig();
        config.setMyDegree("大专");
        assertTrue(ZhilianSearchUrl.build("639", "运营", config, 1).contains("&el=5"));
        config.setMyDegree("本科");
        assertFalse(ZhilianSearchUrl.build("639", "运营", config, 1).contains("el="));
    }
}
