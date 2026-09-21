package com.jobpilot.job51;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Job51JobCardTest {

    private static final String SEARCH_JSON = """
            {
              "status": 1,
              "data": {
                "items": [
                  {
                    "jobId": "152847391",
                    "hrUid": "8821345",
                    "jobName": "陈列设计",
                    "provideSalaryString": "8-13K",
                    "jobAreaString": "苏州-姑苏区",
                    "workYearString": "3-5年",
                    "degreeString": "大专",
                    "fullCompanyName": "苏州某某服饰有限公司",
                    "industryType1Str": "服装/纺织",
                    "companySizeString": "50-150人",
                    "hrName": "王女士",
                    "hrPosition": "招聘主管",
                    "jobHref": "https://we.51job.com/pc/jobdetail?jobId=152847391"
                  },
                  {
                    "jobId": "152847392",
                    "jobName": "视觉设计"
                  }
                ],
                "totalCount": 2
              }
            }
            """;

    @Test
    void 解析真实结构的响应体() {
        List<Job51JobCard> cards = Job51JobCard.parseList(SEARCH_JSON);
        assertEquals(2, cards.size());

        Job51JobCard card = cards.get(0);
        assertEquals("152847391", card.getJobId());
        assertEquals("8821345", card.getRecruiterId());
        assertEquals("陈列设计", card.getJobName());
        assertEquals("8-13K", card.getSalaryDesc());
        assertEquals("苏州-姑苏区", card.getLocationRaw());
        assertEquals("苏州", card.getCityName());
        assertEquals("姑苏区", card.getAreaDistrict());
        assertEquals("3-5年", card.getJobExperience());
        assertEquals("大专", card.getJobDegree());
        assertEquals("苏州某某服饰有限公司", card.getBrandName());
        assertEquals("服装/纺织", card.getIndustryName());
        assertEquals("50-150人", card.getBrandScaleName());
        assertEquals("王女士", card.getBossName());
        assertEquals("招聘主管", card.getBossTitle());
        assertEquals("https://we.51job.com/pc/jobdetail?jobId=152847391", card.getJobUrl());
        assertNull(card.getBossActiveTimeDesc());
        // recruiterId 就是 BossId，去重键靠它
        assertEquals("8821345", card.getBossId());
    }

    @Test
    void 字段缺失的卡片其余列留空但不丢() {
        List<Job51JobCard> cards = Job51JobCard.parseList(SEARCH_JSON);
        Job51JobCard card = cards.get(1);
        assertEquals("152847392", card.getJobId());
        assertEquals("视觉设计", card.getJobName());
        assertNull(card.getRecruiterId());
        assertNull(card.getSalaryDesc());
        assertNull(card.getCityName());
        // 接口没给链接时按 jobId 拼 PC 详情页
        assertEquals("https://we.51job.com/pc/jobdetail?jobId=152847392", card.getJobUrl());
    }

    @Test
    void 移动端键名走兜底分支() {
        String json = """
                {"resultbody": {"job": {"items": [
                  {"jobid": "77", "jobTitle": "陈列师",
                   "salary": "10-15K", "cityName": "上海",
                   "ctmName": "某公司", "hrName": "李女士"}
                ]}}}
                """;
        List<Job51JobCard> cards = Job51JobCard.parseList(json);
        assertEquals(1, cards.size());
        Job51JobCard card = cards.get(0);
        assertEquals("77", card.getJobId());
        assertEquals("陈列师", card.getJobName());
        assertEquals("10-15K", card.getSalaryDesc());
        assertEquals("上海", card.getCityName());
        assertEquals("某公司", card.getBrandName());
        assertEquals("李女士", card.getBossName());
    }

    @Test
    void jobId缺失或为0的实体被丢掉() {
        String json = """
                {"data": {"list": [
                  {"jobName": "无编号"},
                  {"jobId": "0", "jobName": "零编号"},
                  {"jobId": "", "jobName": "空编号"},
                  {"jobId": "9", "jobName": "有编号"}
                ]}}
                """;
        List<Job51JobCard> cards = Job51JobCard.parseList(json);
        assertEquals(1, cards.size());
        assertEquals("9", cards.get(0).getJobId());
    }

    @Test
    void 外层结构全都不是数组时返回空列表() {
        assertTrue(Job51JobCard.parseList("{\"data\": {}}").isEmpty());
        assertTrue(Job51JobCard.parseList("{\"code\": 200}").isEmpty());
        assertTrue(Job51JobCard.parseList("").isEmpty());
        assertTrue(Job51JobCard.parseList(null).isEmpty());
        assertTrue(Job51JobCard.parseList("访问验证页HTML").isEmpty());
    }

    @Test
    void 公司名多个候选按序取第一个非空() throws Exception {
        JsonNode node = new ObjectMapper().readTree(
                "{\"jobId\": \"1\", \"companyName\": \"次选名\"}");
        assertEquals("次选名", Job51JobCard.parse(node).getBrandName());

        node = new ObjectMapper().readTree(
                "{\"jobId\": \"1\", \"ctmName\": \"兜底名\", \"companyName\": \"\"}");
        assertEquals("兜底名", Job51JobCard.parse(node).getBrandName());
    }

    @Test
    void 地区串只有一段时区县留空() throws Exception {
        JsonNode node = new ObjectMapper().readTree(
                "{\"jobId\": \"1\", \"jobAreaString\": \" 上海 \"}");
        Job51JobCard card = Job51JobCard.parse(node);
        assertEquals("上海", card.getCityName());
        assertNull(card.getAreaDistrict());
    }

    @Test
    void 地区串以中横线开头时城市列留空() throws Exception {
        // 真实数据里不会出现，但要保证不会把 "-" 写进城市列
        JsonNode node = new ObjectMapper().readTree(
                "{\"jobId\": \"1\", \"jobAreaString\": \"-姑苏区\"}");
        Job51JobCard card = Job51JobCard.parse(node);
        assertNull(card.getCityName());
        assertEquals("姑苏区", card.getAreaDistrict());
    }

    @Test
    void 地区串只有一个分隔符多出来的部分归区县() throws Exception {
        // 51job 的 jobAreaString 只有一段分隔符，按第一个中横线切即可
        JsonNode node = new ObjectMapper().readTree(
                "{\"jobId\": \"1\", \"jobAreaString\": \"苏州-姑苏区-平江路\"}");
        Job51JobCard card = Job51JobCard.parse(node);
        assertEquals("苏州", card.getCityName());
        assertEquals("姑苏区-平江路", card.getAreaDistrict());
    }

    @Test
    void 地区串用间隔号分隔时也拆() throws Exception {
        // 真机数据：苏州工业园区的岗位给的是 "苏州·苏州工业园区"
        JsonNode node = new ObjectMapper().readTree(
                "{\"jobId\": \"1\", \"jobAreaString\": \"苏州·苏州工业园区\"}");
        Job51JobCard card = Job51JobCard.parse(node);
        assertEquals("苏州", card.getCityName());
        assertEquals("苏州工业园区", card.getAreaDistrict());
    }

    @Test
    void 两种分隔符同时存在时按先出现的切() throws Exception {
        JsonNode node = new ObjectMapper().readTree(
                "{\"jobId\": \"1\", \"jobAreaString\": \"苏州·姑苏区-平江路\"}");
        Job51JobCard card = Job51JobCard.parse(node);
        assertEquals("苏州", card.getCityName());
        assertEquals("姑苏区-平江路", card.getAreaDistrict());
    }

    @Test
    void 非对象节点返回null() {
        assertNull(Job51JobCard.parse(null));
        assertNull(Job51JobCard.parse(new ObjectMapper().createArrayNode()));
        assertNull(Job51JobCard.parse(new ObjectMapper().valueToTree("字符串")));
    }
}
