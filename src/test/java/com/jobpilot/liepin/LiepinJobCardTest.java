package com.jobpilot.liepin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LiepinJobCardTest {

    /** 按搜索接口真实结构构造的响应体：两层 data 包裹 jobCardList */
    private static final String RESPONSE = """
            {
              "code": 0,
              "data": {
                "data": {
                  "jobCardList": [
                    {
                      "job": {
                        "jobId": "12345678",
                        "title": "陈列设计实习生",
                        "salary": "6-8K",
                        "dq": "苏州-姑苏区",
                        "requireWorkYears": "1-3年",
                        "requireEduLevel": "大专",
                        "link": "https://www.liepin.com/job/12345678.shtml",
                        "refreshTime": "20241210175950"
                      },
                      "comp": {
                        "compName": "某某服饰有限公司",
                        "compIndustry": "服装纺织",
                        "compScale": "100-499人"
                      },
                      "recruiter": {
                        "recruiterName": "王女士",
                        "recruiterTitle": "HR",
                        "recruiterId": "998877"
                      }
                    }
                  ]
                }
              }
            }
            """;

    @Test
    void 解析真实结构的响应体() {
        List<LiepinJobCard> cards = LiepinJobCard.parseList(RESPONSE);
        assertEquals(1, cards.size());
        LiepinJobCard card = cards.get(0);
        assertEquals("12345678", card.getJobId());
        assertEquals("998877", card.getBossId());
        assertEquals("陈列设计实习生", card.getJobName());
        assertEquals("6-8K", card.getSalaryDesc());
        assertEquals("苏州", card.getCityName());
        assertEquals("姑苏区", card.getAreaDistrict());
        assertEquals("1-3年", card.getJobExperience());
        assertEquals("大专", card.getJobDegree());
        assertEquals("某某服饰有限公司", card.getBrandName());
        assertEquals("服装纺织", card.getIndustryName());
        assertEquals("100-499人", card.getBrandScaleName());
        assertEquals("王女士", card.getBossName());
        assertEquals("HR", card.getBossTitle());
        assertEquals("https://www.liepin.com/job/12345678.shtml", card.getJobUrl());
        assertEquals("20241210175950", card.getRefreshTime());
    }

    @Test
    void 单层data也能解析() {
        String json = """
                {"data": {"jobCardList": [
                  {"job": {"jobId": "1", "title": "视觉设计"}, "comp": {}, "recruiter": {}}
                ]}}
                """;
        List<LiepinJobCard> cards = LiepinJobCard.parseList(json);
        assertEquals(1, cards.size());
        assertEquals("视觉设计", cards.get(0).getJobName());
    }

    @Test
    void jobId缺失或为0的卡片被丢掉() {
        String json = """
                {"data": {"data": {"jobCardList": [
                  {"job": {"jobId": "0", "title": "无效卡片"}, "comp": {}, "recruiter": {}},
                  {"job": {"title": "没有id"}, "comp": {}, "recruiter": {}}
                ]}}}
                """;
        assertTrue(LiepinJobCard.parseList(json).isEmpty());
    }

    @Test
    void 结构不匹配时返回空列表() {
        assertTrue(LiepinJobCard.parseList("{\"code\":0,\"data\":{}}").isEmpty());
        assertTrue(LiepinJobCard.parseList("{\"code\":0}").isEmpty());
        assertTrue(LiepinJobCard.parseList("").isEmpty());
        assertTrue(LiepinJobCard.parseList("这不是JSON").isEmpty());
    }

    @Test
    void recruiterId缺失时用imId兜底() {
        String json = """
                {"data": {"data": {"jobCardList": [
                  {"job": {"jobId": "1"}, "comp": {}, "recruiter": {"imId": "im-777"}}
                ]}}}
                """;
        LiepinJobCard card = LiepinJobCard.parseList(json).get(0);
        assertEquals("im-777", card.getBossId());
    }

    @Test
    void 猎聘不给JD全文所以postDescription恒为null() {
        LiepinJobCard card = LiepinJobCard.parseList(RESPONSE).get(0);
        assertNull(card.getPostDescription());
        assertNull(card.getBossActiveTimeDesc());
    }

    @Test
    void 空字符串字段规约为null() {
        String json = """
                {"data": {"data": {"jobCardList": [
                  {"job": {"jobId": "1", "title": "", "salary": null}, "comp": {}, "recruiter": {}}
                ]}}}
                """;
        LiepinJobCard card = LiepinJobCard.parseList(json).get(0);
        assertNull(card.getJobName());
        assertNull(card.getSalaryDesc());
    }

    @Test
    void 地区没有中横线时整体当城市() {
        String json = """
                {"data": {"data": {"jobCardList": [
                  {"job": {"jobId": "1", "dq": "全国"}, "comp": {}, "recruiter": {}}
                ]}}}
                """;
        LiepinJobCard card = LiepinJobCard.parseList(json).get(0);
        assertEquals("全国", card.getCityName());
        assertNull(card.getAreaDistrict());
    }
}
