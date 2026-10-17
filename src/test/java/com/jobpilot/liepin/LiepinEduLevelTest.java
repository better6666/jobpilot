package com.jobpilot.liepin;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 猎聘学历筛选。样本是 2026-10-17 真站 cond-init 响应里 educations 的原样（运行时照样从响应读，不写死）。
 * 同一次实测：苏州搜"ai"第一页 42 个岗位，不筛时要求大专/不限的 8 个，带 eduLevel=050 后 41 个
 */
class LiepinEduLevelTest {

    private static final String COND_INIT = """
            {"flag":1,"data":{"educations":[
              {"code":"","name":"不限"},{"code":"010","name":"博士"},{"code":"030","name":"硕士"},
              {"code":"040","name":"本科"},{"code":"050","name":"大专"},{"code":"060","name":"中专/中技"},
              {"code":"080","name":"高中"},{"code":"090","name":"初中及以下"}],
             "yearSalaries":[{"code":"15","name":"15-20万"}]}}
            """;

    @Test
    void 从响应里读出学历选项_空码的不限不要() {
        Map<String, String> options = LiepinEduLevel.parseOptions(COND_INIT);
        assertEquals("050", options.get("大专"));
        assertEquals("010", options.get("博士"));
        assertFalse(options.containsKey("不限"));
        assertTrue(LiepinEduLevel.parseOptions("不是 json").isEmpty());
        assertTrue(LiepinEduLevel.parseOptions("{\"flag\":1,\"data\":{}}").isEmpty());
    }

    @Test
    void 按我的学历挑同一档的码_写法不同也认() {
        Map<String, String> options = LiepinEduLevel.parseOptions(COND_INIT);
        assertEquals("050", LiepinEduLevel.pick(options, "大专"));
        assertEquals("050", LiepinEduLevel.pick(options, "专科"));
        assertEquals("060", LiepinEduLevel.pick(options, "高中/中专"));
        assertEquals("090", LiepinEduLevel.pick(options, "初中及以下"));
        assertNull(LiepinEduLevel.pick(options, ""));
        assertNull(LiepinEduLevel.pick(Map.of(), "大专"));
    }

    @Test
    void 只有大专及以下才在搜索里单选筛() {
        assertTrue(LiepinEduLevel.worthFiltering("大专"));
        assertTrue(LiepinEduLevel.worthFiltering("高中/中专"));
        assertTrue(LiepinEduLevel.worthFiltering("初中及以下"));
        // 本科及以上单选会把"学历不限"和要求更低的岗位一并筛掉，交给本地过滤
        assertFalse(LiepinEduLevel.worthFiltering("本科"));
        assertFalse(LiepinEduLevel.worthFiltering("硕士"));
        assertFalse(LiepinEduLevel.worthFiltering(""));
        assertFalse(LiepinEduLevel.worthFiltering(null));
    }

    @Test
    void 拼进地址且不重复拼() {
        String url = "https://www.liepin.com/zhaopin/?city=060080&dq=060080&currentPage=0&key=ai";
        assertEquals(url + "&eduLevel=050", LiepinEduLevel.withEduLevel(url, "050"));
        assertEquals(url + "&eduLevel=050", LiepinEduLevel.withEduLevel(url + "&eduLevel=050", "040"));
        assertEquals(url, LiepinEduLevel.withEduLevel(url, null));
    }
}
