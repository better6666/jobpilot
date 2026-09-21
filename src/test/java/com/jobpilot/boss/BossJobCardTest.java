package com.jobpilot.boss;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BossJobCardTest {

    /** 按 detail.json 真实结构构造的响应体 */
    private static final String DETAIL_JSON = """
            {
              "code": 0,
              "zpData": {
                "jobInfo": {
                  "encryptId": "job-abc-123",
                  "encryptUserId": "boss-xyz-789",
                  "jobName": "陈列设计实习生",
                  "salaryDesc": "150-300元/天",
                  "locationName": "上海",
                  "areaDistrict": "静安区",
                  "experienceName": "1年以内",
                  "degreeName": "大专",
                  "postDescription": "负责店铺陈列执行",
                  "address": "上海市静安区某某路1号",
                  "jobLabels": ["实习", "陈列"],
                  "skills": [{"name": "陈列设计"}, {"name": "PS"}],
                  "welfareList": ["弹性工作"]
                },
                "brandComInfo": {
                  "brandName": "某某服饰有限公司",
                  "industryName": "服装纺织",
                  "brandScaleName": "100-499人"
                },
                "bossInfo": {
                  "name": "王女士",
                  "title": "HR",
                  "activeTimeDesc": "今日活跃"
                }
              }
            }
            """;

    @Test
    void 解析真实结构的响应体() {
        BossJobCard card = BossJobCard.parse(DETAIL_JSON);
        assertNotNull(card);
        assertEquals("job-abc-123", card.getEncryptId());
        assertEquals("boss-xyz-789", card.getEncryptUserId());
        assertEquals("陈列设计实习生", card.getJobName());
        assertEquals("150-300元/天", card.getSalaryDesc());
        assertEquals("上海", card.getLocationName());
        assertEquals("静安区", card.getAreaDistrict());
        assertEquals("1年以内", card.getExperienceName());
        assertEquals("大专", card.getDegreeName());
        assertEquals("负责店铺陈列执行", card.getPostDescription());
        assertEquals("某某服饰有限公司", card.getBrandName());
        assertEquals("服装纺织", card.getIndustryName());
        assertEquals("王女士", card.getBossName());
        assertEquals("HR", card.getBossTitle());
        assertEquals("今日活跃", card.getBossActiveTimeDesc());
        assertEquals(2, card.getJobLabels().size());
        assertEquals(2, card.getSkills().size());
        assertEquals(1, card.getWelfare().size());
        assertEquals("https://www.zhipin.com/job_detail/job-abc-123.html", card.getJobUrl());
    }

    @Test
    void jobInfo缺失时返回null() {
        assertNull(BossJobCard.parse("{\"code\":0,\"zpData\":{}}"));
        assertNull(BossJobCard.parse("{\"code\":0}"));
        assertNull(BossJobCard.parse(""));
        assertNull(BossJobCard.parse("这不是JSON"));
    }

    @Test
    void HR的id落在bossInfo里时兜底读取() {
        String json = """
                {"zpData": {
                  "jobInfo": {"encryptId": "j1", "jobName": "设计"},
                  "bossInfo": {"name": "李女士", "encryptBossId": "b-fallback"}
                }}
                """;
        BossJobCard card = BossJobCard.parse(json);
        assertNotNull(card);
        assertEquals("b-fallback", card.getEncryptUserId());
        assertEquals("李女士", card.getBossName());
    }

    @Test
    void 空字符串字段规约为null() {
        String json = """
                {"zpData": {"jobInfo": {"encryptId": "j1", "jobName": "", "salaryDesc": null}}}
                """;
        BossJobCard card = BossJobCard.parse(json);
        assertNotNull(card);
        assertNull(card.getJobName());
        assertNull(card.getSalaryDesc());
    }
}
