package com.jobpilot.boss;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BossSearchUrlTest {

    private BossOptions options;

    @BeforeEach
    void setUp() throws Exception {
        options = new BossOptions(new com.fasterxml.jackson.databind.ObjectMapper());
        options.load();
    }

    private BossProperties.BossConfig config() {
        return new BossProperties.BossConfig();
    }

    @Test
    void 基本URL带城市和关键词() {
        String url = BossSearchUrl.build("101020100", "陈列设计", config());
        assertEquals("https://www.zhipin.com/web/geek/jobs?city=101020100&query=%E9%99%88%E5%88%97%E8%AE%BE%E8%AE%A1", url);
    }

    @Test
    void 关键词里的空格和特殊字符被编码() {
        String url = BossSearchUrl.build("101010100", "AI 运营&增长", config());
        assertTrue(url.contains("query=AI+%E8%BF%90%E8%90%A5%26%E5%A2%9E%E9%95%BF"), url);
    }

    @Test
    void 筛选项为空或0时不进URL() {
        BossProperties.BossConfig config = config();
        config.setSalary("");
        config.setDegree("0");
        config.setExperience("104");

        String url = BossSearchUrl.build("101020100", "设计", config);
        assertFalse(url.contains("salary"), url);
        assertFalse(url.contains("degree"), url);
        assertTrue(url.contains("experience=104"), url);
    }

    @Test
    void 所有筛选都空时URL只有城市和关键词() {
        String url = BossSearchUrl.build("101020100", "设计", config());
        assertEquals("https://www.zhipin.com/web/geek/jobs?city=101020100&query=%E8%AE%BE%E8%AE%A1", url);
    }

    @Test
    void 完整筛选全部带上() {
        BossProperties.BossConfig config = config();
        config.setJobType("1901");
        config.setSalary("405");
        config.setExperience("104");
        config.setDegree("202");
        config.setScale("303");
        config.setStage("803");

        String url = BossSearchUrl.build("101280600", "陈列师", config);
        assertTrue(url.contains("jobType=1901"), url);
        assertTrue(url.contains("salary=405"), url);
        assertTrue(url.contains("experience=104"), url);
        assertTrue(url.contains("degree=202"), url);
        assertTrue(url.contains("scale=303"), url);
        assertTrue(url.contains("stage=803"), url);
    }

    @Test
    void 城市码原样返回() {
        assertEquals("101020100", BossSearchUrl.resolveCityCode(options, "101020100"));
        assertEquals("101020100", BossSearchUrl.resolveCityCode(options, " 101020100 "));
    }

    @Test
    void 城市名查码表() {
        assertEquals("101020100", BossSearchUrl.resolveCityCode(options, "上海"));
        assertEquals("101280600", BossSearchUrl.resolveCityCode(options, "深圳"));
    }

    @Test
    void 不存在的城市返回null() {
        assertNull(BossSearchUrl.resolveCityCode(options, "月球基地"));
        assertNull(BossSearchUrl.resolveCityCode(options, ""));
        assertNull(BossSearchUrl.resolveCityCode(options, null));
    }
}
