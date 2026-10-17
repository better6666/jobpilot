package com.jobpilot.lagou;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 结构取自 2026-10-17 拉勾搜索页 __NEXT_DATA__（公司名换成了虚构的） */
class LagouJobCardTest {

    private static final String NEXT_DATA = """
            {"props":{"pageProps":{"query":{},"initData":{"content":{"positionResult":{"totalCount":450,"result":[
              {"positionId":12915109,"positionName":"外卖运营","city":"苏州","district":"昆山市","salary":"6k-9k",
               "workYear":"1-3年","education":"本科","companyFullName":"某某便利有限公司","companyShortName":"某某便利",
               "industryField":"新零售","companySize":"150-500人","publisherId":27380905,
               "positionDetail":"岗位职责:\\n<br />1.外卖平台门店运营；\\n<br /><p>2.数据分析</p>"},
              {"positionId":0,"positionName":"坏数据"}
            ]}}}}}}
            """;

    @Test
    void 从NEXT_DATA读出卡片和总数() {
        LagouJobCard.Page page = LagouJobCard.parse(NEXT_DATA);
        assertEquals(450, page.total());
        assertEquals(1, page.cards().size(), "positionId 为 0 的丢掉");
        LagouJobCard c = page.cards().get(0);
        assertEquals("12915109", c.getJobId());
        assertEquals("27380905", c.getBossId());
        assertEquals("外卖运营", c.getJobName());
        assertEquals("苏州", c.getCityName());
        assertEquals("昆山市", c.getAreaDistrict());
        assertEquals("1-3年", c.getJobExperience());
        assertEquals("本科", c.getJobDegree());
        assertEquals("某某便利有限公司", c.getBrandName());
        assertEquals("新零售", c.getIndustryName());
        assertEquals("150-500人", c.getBrandScaleName());
        assertEquals("https://www.lagou.com/wn/jobs/12915109.html", c.getJobUrl());
        assertEquals("岗位职责:\n1.外卖平台门店运营；\n2.数据分析", c.getPostDescription());
    }

    @Test
    void 坏数据不抛异常() {
        assertTrue(LagouJobCard.parse(null).cards().isEmpty());
        assertTrue(LagouJobCard.parse("<html>").cards().isEmpty());
    }

    @Test
    void 搜索地址_翻页和大专学历筛选() {
        assertEquals("https://www.lagou.com/wn/zhaopin?kd=%E8%BF%90%E8%90%A5&city=%E8%8B%8F%E5%B7%9E",
                LagouSearchUrl.build("苏州", "运营", "", 1));
        assertEquals("https://www.lagou.com/wn/zhaopin?kd=%E8%BF%90%E8%90%A5&city=%E8%8B%8F%E5%B7%9E&pn=2&xl=%E5%A4%A7%E4%B8%93",
                LagouSearchUrl.build("苏州", "运营", "大专", 2));
        // 本科及以上、中专高中都不在搜索里筛（拉勾单选，且没有中专档）
        assertFalse(LagouSearchUrl.build("苏州", "运营", "本科", 1).contains("xl="));
        assertFalse(LagouSearchUrl.build("苏州", "运营", "高中/中专", 1).contains("xl="));
    }
}
