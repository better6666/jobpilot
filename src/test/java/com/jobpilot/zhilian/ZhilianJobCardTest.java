package com.jobpilot.zhilian;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ZhilianJobCardTest {

    @Test
    void 从职位详情链接提jobId() {
        assertEquals("1234567",
                ZhilianJobCard.extractJobId("https://jobs.zhaopin.com/company/1234567/jobdetail/1234567.htm"));
        assertEquals("CC120812345J00382567890",
                ZhilianJobCard.extractJobId(
                        "https://sou.zhaopin.com/?jl=639&kw=%E9%99%88%E5%88%97#jobdetail/CC120812345J00382567890.htm"));
    }

    @Test
    void 没有jobdetail段的链接返回null() {
        // 老工程是从公司名链接提 jobId，那是条永远不命中的死分支
        assertNull(ZhilianJobCard.extractJobId("https://company.zhaopin.com/company/1234567.htm"));
        assertNull(ZhilianJobCard.extractJobId("https://www.zhaopin.com/"));
        assertNull(ZhilianJobCard.extractJobId(null));
        assertNull(ZhilianJobCard.extractJobId(""));
        assertNull(ZhilianJobCard.extractJobId("jobdetail/"));
        assertNull(ZhilianJobCard.extractJobId("jobdetail/   .htm"));
    }

    @Test
    void jobdetail之后还有别的路径时取到htm为止() {
        assertEquals("999",
                ZhilianJobCard.extractJobId("https://jobs.zhaopin.com/x/jobdetail/999.htm?from=a"));
    }

    @Test
    void HR标识只能是null() {
        ZhilianJobCard card = new ZhilianJobCard();
        card.setJobId("1");
        // 智联列表接口不给 HR id，去重键的一半是空的
        assertNull(card.getBossId());
        assertNull(card.getBossActiveTimeDesc());
    }

    @Test
    void 地区串拆成城市和区县() {
        ZhilianJobCard card = new ZhilianJobCard();
        card.setLocationRaw("苏州-姑苏区");
        ZhilianJobCard.splitLocation(card);
        assertEquals("苏州", card.getCityName());
        assertEquals("姑苏区", card.getAreaDistrict());
    }

    @Test
    void 地区串用间隔号分隔时也拆() {
        // 面板 ul.job-detail-summary__tags 第一个 li 实测就是"苏州·常熟市"
        ZhilianJobCard card = new ZhilianJobCard();
        card.setLocationRaw("苏州·常熟市");
        ZhilianJobCard.splitLocation(card);
        assertEquals("苏州", card.getCityName());
        assertEquals("常熟市", card.getAreaDistrict());
    }

    @Test
    void 地区串用空格分隔时也拆() {
        // 列表卡片给的是"苏州 工业园区"这种
        ZhilianJobCard card = new ZhilianJobCard();
        card.setLocationRaw("苏州 常熟 常福");
        ZhilianJobCard.splitLocation(card);
        assertEquals("苏州", card.getCityName());
        assertEquals("常熟 常福", card.getAreaDistrict());
    }

    @Test
    void 分隔符混用时按最先出现的那个切() {
        ZhilianJobCard card = new ZhilianJobCard();
        card.setLocationRaw("苏州·姑苏区-平江路");
        ZhilianJobCard.splitLocation(card);
        assertEquals("苏州", card.getCityName());
        assertEquals("姑苏区-平江路", card.getAreaDistrict());
    }

    @Test
    void 地区串里的点号不当分隔符() {
        // "1.5万"这种金额不会走到这里，但"工业.园区"里的点要能切开
        ZhilianJobCard card = new ZhilianJobCard();
        card.setLocationRaw("工业.园区");
        ZhilianJobCard.splitLocation(card);
        assertEquals("工业", card.getCityName());
        assertEquals("园区", card.getAreaDistrict());
    }

    @Test
    void 地区串只有一段时区县留空() {
        ZhilianJobCard card = new ZhilianJobCard();
        card.setLocationRaw(" 上海 ");
        ZhilianJobCard.splitLocation(card);
        assertEquals("上海", card.getCityName());
        assertNull(card.getAreaDistrict());
    }

    @Test
    void 地区串以中横线开头时城市列留空() {
        ZhilianJobCard card = new ZhilianJobCard();
        card.setLocationRaw("-姑苏区");
        ZhilianJobCard.splitLocation(card);
        assertNull(card.getCityName());
        assertEquals("姑苏区", card.getAreaDistrict());
    }

    @Test
    void 地区串为空时两列都留空() {
        ZhilianJobCard card = new ZhilianJobCard();
        card.setLocationRaw("");
        ZhilianJobCard.splitLocation(card);
        assertNull(card.getCityName());
        assertNull(card.getAreaDistrict());

        card.setLocationRaw(null);
        ZhilianJobCard.splitLocation(card);
        assertNull(card.getCityName());
    }

    @Test
    void 采不到的字段默认全null由打分规则自然不命中() {
        ZhilianJobCard card = new ZhilianJobCard();
        assertNull(card.getJobDegree());
        assertNull(card.getJobExperience());
        assertNull(card.getIndustryName());
        assertNull(card.getBrandScaleName());
        assertNull(card.getPostDescription());
        assertNull(card.getBossName());
    }
}
