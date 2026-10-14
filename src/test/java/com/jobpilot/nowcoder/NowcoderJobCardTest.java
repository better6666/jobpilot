package com.jobpilot.nowcoder;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 字段取自 2026-10-14 牛客搜索页 .job-card-item 的真实文字 */
class NowcoderJobCardTest {

    private static Map<String, Object> fields(String href, String title, String salary, List<String> tags,
                                              String company, List<String> companyInfo, String text) {
        Map<String, Object> m = new HashMap<>();
        m.put("href", href);
        m.put("title", title);
        m.put("salary", salary);
        m.put("tags", tags);
        m.put("company", company);
        m.put("companyInfo", companyInfo);
        m.put("text", text);
        return m;
    }

    @Test
    void 校招卡片按长相分字段() {
        NowcoderJobCard c = NowcoderDriver.cardFrom(fields(
                "https://www.nowcoder.com/jobs/detail/462512?deliverSource=21&pageSource=5021",
                "校招 | 校招-内容运营（短视频）", "8-11K·12薪",
                List.of("HR刚处理简历", "深圳", "2027届", "本科"),
                "水滴", List.of("互联网综合 ", "1000-9999人"), "校招 | 校招-内容运营（短视频）"));
        assertEquals("462512", c.getJobId());
        assertEquals("https://www.nowcoder.com/jobs/detail/462512", c.getJobUrl());
        assertEquals("校招", c.getRecruitType());
        assertEquals("校招-内容运营（短视频）", c.getJobName());
        assertEquals("8-11K·12薪", c.getSalaryDesc());
        assertEquals("HR刚处理简历", c.getBossActiveTimeDesc());
        assertEquals("深圳", c.getCityName());
        assertEquals("2027届", c.getJobExperience());
        assertEquals("本科", c.getJobDegree());
        assertEquals("水滴", c.getBrandName());
        assertEquals("互联网综合", c.getIndustryName());
        assertEquals("1000-9999人", c.getBrandScaleName());
        assertFalse(c.isExternal());
    }

    @Test
    void 实习标签不当城市_社招年限认得出() {
        NowcoderJobCard intern = NowcoderDriver.cardFrom(fields("/jobs/detail/1", "实习 | 游戏运营", "300-500元/天",
                List.of("HR刚处理简历", "广州", "5天/周", "最少3个月", "有转正", "本科"), "4399游戏",
                List.of("游戏", "1000-9999人"), ""));
        assertEquals("实习", intern.getRecruitType());
        assertEquals("广州", intern.getCityName());
        assertEquals("本科", intern.getJobDegree());

        NowcoderJobCard social = NowcoderDriver.cardFrom(fields("/jobs/detail/2", "社招 | 运营", "25-50K·15薪",
                List.of("北京/上海/杭州/广州", "1-3年", "本科", "产品运营"), "字节跳动",
                List.of("互联网综合", "10000人以上"), ""));
        assertEquals("北京/上海/杭州/广州", social.getCityName());
        assertEquals("1-3年", social.getJobExperience());
    }

    @Test
    void 官网直投标出来_没有详情链接的丢掉() {
        NowcoderJobCard c = NowcoderDriver.cardFrom(fields("/jobs/detail/3", "产品运营-内推码DNK2026", "薪资面议",
                List.of("北京/深圳/天津"), "联想", List.of(), "产品运营 直达官网 投后必反馈"));
        assertTrue(c.isExternal());
        assertNull(c.getRecruitType(), "没有「校招 | 」前缀就不猜类型");
        assertNull(NowcoderDriver.cardFrom(fields("/enterprise/1175", "x", null, List.of(), null, List.of(), "")));
    }

    @Test
    void 本地过滤_官网直投_多城市按包含_类型不符() {
        NowcoderProperties.NowcoderConfig config = new NowcoderProperties.NowcoderConfig();
        NowcoderJobCard c = new NowcoderJobCard();
        c.setCityName("北京/上海/杭州");
        c.setRecruitType("社招");
        assertNull(NowcoderService.localReject(c, config));

        config.setCity("上海");
        assertNull(NowcoderService.localReject(c, config));
        config.setCity("苏州");
        assertTrue(NowcoderService.localReject(c, config).startsWith("城市不符"));

        config.setCity("");
        config.setRecruitType("校招");
        assertTrue(NowcoderService.localReject(c, config).startsWith("招聘类型不符"));

        c.setExternal(true);
        assertEquals("官网直投岗位，要去企业官网自己填表", NowcoderService.localReject(c, config));
    }

    @Test
    void 搜索地址() {
        assertEquals("https://www.nowcoder.com/search/job?type=job&query=%E8%BF%90%E8%90%A5",
                NowcoderDriver.searchUrl(" 运营 "));
    }
}
