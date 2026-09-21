package com.jobpilot.delivery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JobScorerTest {

    private final JobScorer emptyScorer = new JobScorer(new ScoreRules());

    /** 只填打分用得到的字段，其余留 null——正好覆盖"字段采不到"的情况。 */
    private static class Card implements JobCard {
        private String jobName;
        private String jobDegree;
        private String jobExperience;
        private String industryName;
        private String postDescription;

        @Override public String getJobId() { return null; }
        @Override public String getBossId() { return null; }
        @Override public String getJobName() { return jobName; }
        @Override public String getBrandName() { return null; }
        @Override public String getSalaryDesc() { return null; }
        @Override public String getCityName() { return null; }
        @Override public String getAreaDistrict() { return null; }
        @Override public String getJobExperience() { return jobExperience; }
        @Override public String getJobDegree() { return jobDegree; }
        @Override public String getBossName() { return null; }
        @Override public String getBossTitle() { return null; }
        @Override public String getJobUrl() { return null; }
        @Override public String getPostDescription() { return postDescription; }
        @Override public String getIndustryName() { return industryName; }
        @Override public String getBrandScaleName() { return null; }
        @Override public String getBossActiveTimeDesc() { return null; }
    }

    private Card card(String jobName, String degree, String experience, String industry, String jd) {
        Card card = new Card();
        card.jobName = jobName;
        card.jobDegree = degree;
        card.jobExperience = experience;
        card.industryName = industry;
        card.postDescription = jd;
        return card;
    }

    private ScoreRules.Rule rule(String match, int score) {
        ScoreRules.Rule rule = new ScoreRules.Rule();
        rule.setMatch(match);
        rule.setScore(score);
        return rule;
    }

    @Test
    void 默认规则全空且阈值为0时任何岗位都通过() {
        ScoreResult result = emptyScorer.score(card("随便什么岗位", "本科", "3-5年", "软件", "岗位描述"));
        assertTrue(result.isPass());
        assertEquals(0, result.getScore());
    }

    @Test
    void 职位黑名单一票否决() {
        ScoreRules rules = new ScoreRules();
        rules.setTitleReject(List.of("销售", "客服"));
        JobScorer scorer = new JobScorer(rules);

        ScoreResult result = scorer.score(card("电话销售专员", "大专", "1年以内", "零售", ""));
        assertFalse(result.isPass());
        assertEquals(-1000, result.getScore());
        assertTrue(result.getReason().contains("职位黑名单命中"));
        assertTrue(result.getReason().contains("销售"));
    }

    @Test
    void 学历黑名单一票否决() {
        ScoreRules rules = new ScoreRules();
        rules.setDegreeReject(List.of("博士"));
        JobScorer scorer = new JobScorer(rules);

        ScoreResult result = scorer.score(card("研究员", "博士", "经验不限", "科研", ""));
        assertFalse(result.isPass());
        assertTrue(result.getReason().contains("学历黑名单命中"));
    }

    @Test
    void 规则加分超过阈值才通过() {
        ScoreRules rules = new ScoreRules();
        rules.setThreshold(20);
        rules.setJobRules(List.of(rule("陈列", 25), rule("设计", 10)));
        JobScorer scorer = new JobScorer(rules);

        assertTrue(scorer.score(card("陈列设计实习生", "大专", "应届", "", "")).isPass());
        // 只命中"设计"（+10），到不了 20
        assertFalse(scorer.score(card("平面设计", "大专", "应届", "", "")).isPass());
    }

    @Test
    void 规则扣分可以把岗位压到阈值以下() {
        ScoreRules rules = new ScoreRules();
        rules.setThreshold(10);
        rules.setExperienceRules(List.of(rule("5年", -30)));
        JobScorer scorer = new JobScorer(rules);

        // 猎聘的经验字段形如"5年以上"，包含"5年"
        ScoreResult result = scorer.score(card("设计助理", "大专", "5年以上", "", ""));
        assertFalse(result.isPass());
        assertEquals(-30, result.getScore());
    }

    @Test
    void 匹配大小写不敏感() {
        ScoreRules rules = new ScoreRules();
        rules.setThreshold(10);
        rules.setIndustryRules(List.of(rule("ai", 25)));
        JobScorer scorer = new JobScorer(rules);

        // 岗位方写"AI"，规则方写"ai"，也要命中
        assertTrue(scorer.score(card("AI 运营", "本科", "1年以内", "AI", "")).isPass());
    }

    @Test
    void JD规则在岗位描述里匹配() {
        ScoreRules rules = new ScoreRules();
        rules.setThreshold(10);
        rules.setJdRules(List.of(rule("专业不限", 15), rule("仅限计算机", -30)));
        JobScorer scorer = new JobScorer(rules);

        assertTrue(scorer.score(card("运营", "大专", "1年以内", "零售", "我们专业不限，欢迎投递")).isPass());
        assertFalse(scorer.score(card("运营", "大专", "1年以内", "零售", "仅限计算机专业")).isPass());
    }

    @Test
    void 多组规则分数累加() {
        ScoreRules rules = new ScoreRules();
        rules.setThreshold(30);
        rules.setDegreeRules(List.of(rule("大专", 10)));
        rules.setExperienceRules(List.of(rule("应届", 10)));
        rules.setIndustryRules(List.of(rule("服装", 10)));
        rules.setJobRules(List.of(rule("陈列", 10)));
        JobScorer scorer = new JobScorer(rules);

        ScoreResult result = scorer.score(card("陈列设计", "大专", "应届毕业生", "服装纺织", ""));
        assertTrue(result.isPass());
        assertEquals(40, result.getScore());
        // reason 里能看到每组命中记录，方便用户复盘
        assertTrue(result.getReason().contains("职位[陈列]+10"));
        assertTrue(result.getReason().contains("学历[大专]+10"));
        assertTrue(result.getReason().contains("经验[应届]+10"));
        assertTrue(result.getReason().contains("行业[服装]+10"));
    }

    @Test
    void 字段为null不会抛异常() {
        ScoreRules rules = new ScoreRules();
        rules.setThreshold(10);
        rules.setJobRules(List.of(rule("设计", 10)));
        rules.setTitleReject(List.of("销售"));
        JobScorer scorer = new JobScorer(rules);

        // 全 null 字段：得 0 分，到不了阈值 10
        ScoreResult result = scorer.score(new Card());
        assertFalse(result.isPass());
        assertEquals(0, result.getScore());
        assertEquals("无命中规则", result.getReason());
    }

    @Test
    void 黑名单优先于加分判定() {
        ScoreRules rules = new ScoreRules();
        rules.setThreshold(5);
        rules.setJobRules(List.of(rule("设计", 50)));
        rules.setTitleReject(List.of("销售"));
        JobScorer scorer = new JobScorer(rules);

        ScoreResult result = scorer.score(card("设计销售总监", "本科", "3-5年", "", ""));
        assertFalse(result.isPass());
        assertTrue(result.getReason().contains("职位黑名单"));
    }
}
