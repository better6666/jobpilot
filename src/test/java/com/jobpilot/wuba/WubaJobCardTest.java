package com.jobpilot.wuba;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 字段形态取自 2026-10-17 苏州站登录后的真实列表项（公司名换成了虚构的） */
class WubaJobCardTest {

    private static Map<String, Object> row() {
        Map<String, Object> m = new HashMap<>();
        m.put("infoid", "68113775981600");
        m.put("title", " 杨舍 | 文化传媒公司运营客服3k起 ");
        m.put("href", "https://su.58.com/kefu/68113775981600x.shtml?psid=1&entinfo=68113775981600_");
        m.put("salary", "3000-5000元/月");
        m.put("company", "某某文化传媒有限公司");
        m.put("cate", "客服专员/助理");
        m.put("xueli", "中专");
        m.put("jingyan", "1-2年");
        m.put("zpim", "%7B%22rootcateid%22%3A%22574%22%2C%22userid%22%3A%2232212877048586%22%2C%22postid%22%3A%2268113775981600%22%7D");
        m.put("btn", "申请");
        return m;
    }

    @Test
    void 列表项字段全部读到() {
        WubaJobCard c = WubaJobCard.fromFields(row(), "苏州");
        assertEquals("68113775981600", c.getJobId());
        assertEquals("杨舍", c.getAreaDistrict());
        assertEquals("文化传媒公司运营客服3k起", c.getJobName());
        assertEquals("3000-5000元/月", c.getSalaryDesc());
        assertEquals("某某文化传媒有限公司", c.getBrandName());
        assertEquals("客服专员/助理", c.getIndustryName());
        assertEquals("中专", c.getJobDegree());
        assertEquals("1-2年", c.getJobExperience());
        assertEquals("32212877048586", c.getBossId());
        assertEquals("https://su.58.com/kefu/68113775981600x.shtml", c.getJobUrl());
        assertEquals("苏州", c.getCityName());
        assertFalse(c.isApplied());
    }

    @Test
    void 已申请的认得出_没有岗位ID的丢掉() {
        Map<String, Object> m = row();
        m.put("btn", "已申请");
        assertTrue(WubaJobCard.fromFields(m, "苏州").isApplied());
        m.put("infoid", null);
        assertNull(WubaJobCard.fromFields(m, "苏州"));
    }

    @Test
    void 标题没有区域前缀时整段当岗位名() {
        Map<String, Object> m = row();
        m.put("title", "门店运营专员");
        WubaJobCard c = WubaJobCard.fromFields(m, "苏州");
        assertEquals("门店运营专员", c.getJobName());
        assertNull(c.getAreaDistrict());
    }

    @Test
    void 搜索地址_城市是二级域名_薪资是路径段() {
        assertEquals("https://su.58.com/quanzhizhaopin/?key=%E8%BF%90%E8%90%A5", WubaSearchUrl.build("su", "运营", "0"));
        assertEquals("https://su.58.com/quanzhizhaopin/pve_5354_3000_4999/?key=%E8%BF%90%E8%90%A5",
                WubaSearchUrl.build("su", "运营", "3000_4999"));
        assertEquals("https://www.58.com/quanzhizhaopin/?key=%E8%BF%90%E8%90%A5", WubaSearchUrl.build(null, "运营", null));
    }

    @Test
    void 读不出userid时返回空() {
        assertNull(WubaJobCard.userIdFrom(null));
        assertNull(WubaJobCard.userIdFrom("%7B%7D"));
    }

    @Test
    void 整页卡片按JSON解析_每行各是各的() {
        java.util.List<Map<String, Object>> rows = WubaDriver.parseRows(
                "[{\"infoid\":\"1\",\"title\":\"A\"},{\"infoid\":\"2\",\"title\":\"B\"}]");
        assertEquals(2, rows.size());
        assertEquals("1", rows.get(0).get("infoid"));
        assertEquals("2", rows.get(1).get("infoid"));
        assertTrue(WubaDriver.parseRows("坏数据").isEmpty());
    }
}
