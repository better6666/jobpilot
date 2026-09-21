package com.jobpilot.zhilian;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 面板头部那排无类名 span 和公司 meta 串的解析。
 * 这两个方法是纯静态的，不需要浏览器。
 */
class ZhilianPanelParseTest {

    private static ZhilianJobCard facts(String... items) {
        ZhilianJobCard card = new ZhilianJobCard();
        ZhilianDriver.applySummaryFacts(card, Arrays.asList(items));
        return card;
    }

    @Test
    void 按内容特征认经验和学历() {
        ZhilianJobCard card = facts("苏州-姑苏区", "3-5年", "本科", "招3人");
        assertEquals("3-5年", card.getJobExperience());
        assertEquals("本科", card.getJobDegree());
    }

    @Test
    void 学历可能缺席经验可能写成年以下() {
        ZhilianJobCard card = facts("苏州-姑苏区", "1年以下", "招2人");
        assertEquals("1年以下", card.getJobExperience());
        assertNull(card.getJobDegree());
    }

    @Test
    void 经验可能缺席只留学历() {
        ZhilianJobCard card = facts("苏州-姑苏区", "学历不限");
        assertNull(card.getJobExperience());
        assertEquals("学历不限", card.getJobDegree());
    }

    @Test
    void 招聘人数那一项不认() {
        // "招N人"里也带"人"，但不带"年"也不在学历词表里，本来就不会误认；
        // 这里锁死的是它不会被当成别的东西
        ZhilianJobCard card = facts("苏州-姑苏区", "招10人");
        assertNull(card.getJobExperience());
        assertNull(card.getJobDegree());
    }

    @Test
    void 地区词不会被误认成经验() {
        // 苏州工业园区带"年"吗？不带。锁死这个边界防止以后乱加判据
        ZhilianJobCard card = facts("苏州工业园区", "3-5年", "大专");
        assertEquals("3-5年", card.getJobExperience());
        assertEquals("大专", card.getJobDegree());
    }

    @Test
    void 空串和null都不炸() {
        ZhilianJobCard card = new ZhilianJobCard();
        ZhilianDriver.applySummaryFacts(card, null);
        ZhilianDriver.applySummaryFacts(card, List.of());
        ZhilianDriver.applySummaryFacts(card, Arrays.asList("  ", null, "3-5年"));
        assertEquals("3-5年", card.getJobExperience());

        ZhilianDriver.applySummaryFacts(null, List.of("3-5年"));
    }

    @Test
    void 公司meta拆出规模和行业() {
        ZhilianJobCard card = new ZhilianJobCard();
        ZhilianDriver.splitCompanyMeta(card, "/100-299人 · 鞋服箱包批发/零售/贸易");
        assertEquals("100-299人", card.getBrandScaleName());
        assertEquals("鞋服箱包批发/零售/贸易", card.getIndustryName());
    }

    @Test
    void 公司meta三段时规模按含人认行业取末段() {
        // 实测还有这种：融资阶段 · 规模 · 行业，行业自己带顿号
        ZhilianJobCard card = new ZhilianJobCard();
        ZhilianDriver.splitCompanyMeta(card, "/未融资 · 20-99人 · 广告/公关/营销、会议/展览/活动");
        assertEquals("20-99人", card.getBrandScaleName());
        assertEquals("广告/公关/营销、会议/展览/活动", card.getIndustryName());
    }

    @Test
    void 公司meta只有规模时行业留空() {
        ZhilianJobCard card = new ZhilianJobCard();
        ZhilianDriver.splitCompanyMeta(card, "20人以下");
        assertEquals("20人以下", card.getBrandScaleName());
        assertNull(card.getIndustryName());
    }

    @Test
    void 公司meta用竖线分隔也行() {
        ZhilianJobCard card = new ZhilianJobCard();
        ZhilianDriver.splitCompanyMeta(card, "1000-9999人|服装/纺织");
        assertEquals("1000-9999人", card.getBrandScaleName());
        assertEquals("服装/纺织", card.getIndustryName());
    }

    @Test
    void 公司meta第一段不是规模时也能取到行业() {
        ZhilianJobCard card = new ZhilianJobCard();
        ZhilianDriver.splitCompanyMeta(card, "民营公司 · 服装/纺织 · 100-299人");
        assertEquals("100-299人", card.getBrandScaleName());
        // 末段是规模，不能当行业
        assertNull(card.getIndustryName());
    }

    @Test
    void 公司meta为空或null都不炸() {
        ZhilianJobCard card = new ZhilianJobCard();
        ZhilianDriver.splitCompanyMeta(card, null);
        ZhilianDriver.splitCompanyMeta(card, "   ");
        assertNull(card.getBrandScaleName());
        assertNull(card.getIndustryName());

        ZhilianDriver.splitCompanyMeta(null, "100-299人 · 服装");
    }
}
