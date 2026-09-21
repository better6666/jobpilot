package com.jobpilot.liepin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LiepinSearchUrlTest {

    private LiepinOptions options;

    @BeforeEach
    void setUp() throws Exception {
        options = new LiepinOptions(new com.fasterxml.jackson.databind.ObjectMapper());
        options.load();
    }

    private LiepinProperties.LiepinConfig config() {
        return new LiepinProperties.LiepinConfig();
    }

    @Test
    void 城市和dq双写() {
        String url = LiepinSearchUrl.build("060080", "陈列设计", config());
        // 只写 city 大区筛选不生效，两个参数必须同值
        assertEquals("https://www.liepin.com/zhaopin/?city=060080&dq=060080&currentPage=0"
                + "&key=%E9%99%88%E5%88%97%E8%AE%BE%E8%AE%A1", url);
    }

    @Test
    void 城市留空时两个参数整个省略() {
        String url = LiepinSearchUrl.build("", "陈列设计", config());
        assertFalse(url.contains("city"), url);
        assertFalse(url.contains("dq"), url);
        assertEquals("https://www.liepin.com/zhaopin/?currentPage=0"
                + "&key=%E9%99%88%E5%88%97%E8%AE%BE%E8%AE%A1", url);
    }

    @Test
    void 城市码是null时同样省略() {
        String url = LiepinSearchUrl.build(null, "设计", config());
        assertFalse(url.contains("city"), url);
        assertFalse(url.contains("dq="), url);
    }

    @Test
    void 关键词被URL编码() {
        String url = LiepinSearchUrl.build("010", "AI 运营&增长", config());
        // URLEncoder 把空格编成 +，和浏览器地址栏一致
        assertTrue(url.contains("key=AI+%E8%BF%90%E8%90%A5%26%E5%A2%9E%E9%95%BF"), url);
    }

    @Test
    void 关键词为空时只带currentPage() {
        String url = LiepinSearchUrl.build("020", "  ", config());
        assertEquals("https://www.liepin.com/zhaopin/?city=020&dq=020&currentPage=0", url);
    }

    @Test
    void 薪资留空或为0时省略() {
        LiepinProperties.LiepinConfig config = config();
        config.setSalary("");
        assertEquals("https://www.liepin.com/zhaopin/?city=010&dq=010&currentPage=0&key=%E8%AE%BE%E8%AE%A1",
                LiepinSearchUrl.build("010", "设计", config));

        config.setSalary("0");
        assertFalse(LiepinSearchUrl.build("010", "设计", config).contains("salary"));
    }

    @Test
    void 薪资有值时带上() {
        LiepinProperties.LiepinConfig config = config();
        config.setSalary("15");
        String url = LiepinSearchUrl.build("070020", "陈列设计", config);
        assertTrue(url.contains("salary=15"), url);
        // 参数顺序固定：city, dq, salary, currentPage, key
        assertEquals("https://www.liepin.com/zhaopin/?city=070020&dq=070020&salary=15&currentPage=0"
                + "&key=%E9%99%88%E5%88%97%E8%AE%BE%E8%AE%A1", url);
    }

    @Test
    void 城市码原样返回() {
        assertEquals("060080", LiepinSearchUrl.resolveCityCode(options, "060080"));
        assertEquals("410", LiepinSearchUrl.resolveCityCode(options, " 410 "));
    }

    @Test
    void 城市名查码表() {
        assertEquals("060080", LiepinSearchUrl.resolveCityCode(options, "苏州"));
        assertEquals("010", LiepinSearchUrl.resolveCityCode(options, "北京"));
    }

    @Test
    void 不存在的城市返回null() {
        assertNull(LiepinSearchUrl.resolveCityCode(options, "月球基地"));
        assertNull(LiepinSearchUrl.resolveCityCode(options, ""));
        assertNull(LiepinSearchUrl.resolveCityCode(options, null));
    }

    @Test
    void 七位以上的数字不当成城市码() {
        // Boss 的 9 位码粘到这里必须查码表而不是原样透传
        assertNull(LiepinSearchUrl.resolveCityCode(options, "101020100"));
    }
}
