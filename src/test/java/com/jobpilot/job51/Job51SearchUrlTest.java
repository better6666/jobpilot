package com.jobpilot.job51;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Job51SearchUrlTest {

    private Job51Options options;

    @BeforeEach
    void setUp() throws Exception {
        options = new Job51Options(new com.fasterxml.jackson.databind.ObjectMapper());
        options.load();
    }

    private Job51Properties.Job51Config config() {
        return new Job51Properties.Job51Config();
    }

    @Test
    void 基本URL带地区和关键词() {
        String url = Job51SearchUrl.build("070300", "陈列设计", config());
        assertEquals("https://we.51job.com/pc/search?jobArea=070300"
                + "&keyword=%E9%99%88%E5%88%97%E8%AE%BE%E8%AE%A1", url);
    }

    @Test
    void 关键词里的空格和特殊字符被编码() {
        String url = Job51SearchUrl.build("010000", "AI 运营&增长", config());
        assertTrue(url.contains("keyword=AI+%E8%BF%90%E8%90%A5%26%E5%A2%9E%E9%95%BF"), url);
    }

    @Test
    void 地区码为0时整个参数省略() {
        // 传 jobArea=0 会搜出空结果，必须整个省掉
        String url = Job51SearchUrl.build("0", "设计", config());
        assertFalse(url.contains("jobArea"), url);
        assertEquals("https://we.51job.com/pc/search?keyword=%E8%AE%BE%E8%AE%A1", url);
    }

    @Test
    void 地区码为空时同样省略() {
        assertFalse(Job51SearchUrl.build("", "设计", config()).contains("jobArea"));
        assertFalse(Job51SearchUrl.build(null, "设计", config()).contains("jobArea"));
    }

    @Test
    void 薪资为0时省略() {
        Job51Properties.Job51Config config = config();
        config.setSalary("0");
        assertFalse(Job51SearchUrl.build("070300", "设计", config).contains("salary"));

        config.setSalary("");
        assertFalse(Job51SearchUrl.build("070300", "设计", config).contains("salary"));
    }

    @Test
    void 薪资有值时带上且保留前导零() {
        Job51Properties.Job51Config config = config();
        config.setSalary("05");
        String url = Job51SearchUrl.build("070300", "陈列设计", config);
        assertEquals("https://we.51job.com/pc/search?jobArea=070300&salary=05"
                + "&keyword=%E9%99%88%E5%88%97%E8%AE%BE%E8%AE%A1", url);
    }

    @Test
    void 参数顺序固定为地区薪资关键词() {
        Job51Properties.Job51Config config = config();
        config.setSalary("08");
        String url = Job51SearchUrl.build("080200", "陈列设计", config);
        assertEquals("https://we.51job.com/pc/search?jobArea=080200&salary=08"
                + "&keyword=%E9%99%88%E5%88%97%E8%AE%BE%E8%AE%A1", url);
    }

    @Test
    void 什么都没配时只有关键词() {
        assertEquals("https://we.51job.com/pc/search?keyword=%E8%AE%BE%E8%AE%A1",
                Job51SearchUrl.build(null, "设计", config()));
    }

    @Test
    void 城市码原样返回() {
        assertEquals("070300", Job51SearchUrl.resolveCityCode(options, "070300"));
        assertEquals("010000", Job51SearchUrl.resolveCityCode(options, " 010000 "));
    }

    @Test
    void 城市名查码表() {
        assertEquals("070300", Job51SearchUrl.resolveCityCode(options, "苏州"));
        assertEquals("010000", Job51SearchUrl.resolveCityCode(options, "北京"));
    }

    @Test
    void 不存在的城市返回null() {
        assertNull(Job51SearchUrl.resolveCityCode(options, "月球基地"));
        assertNull(Job51SearchUrl.resolveCityCode(options, ""));
        assertNull(Job51SearchUrl.resolveCityCode(options, null));
    }

    @Test
    void 非六位数字不当成地区码() {
        // 猎聘的 3 位码粘到这里必须查码表而不是原样透传
        assertNull(Job51SearchUrl.resolveCityCode(options, "010"));
    }
}
