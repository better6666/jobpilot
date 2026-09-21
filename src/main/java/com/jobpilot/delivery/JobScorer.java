package com.jobpilot.delivery;

import java.util.ArrayList;
import java.util.List;

/**
 * 岗位打分。纯函数，不碰浏览器和数据库，方便单测。
 *
 * 计算顺序：先一票否决，再逐组规则加减分，最后与阈值比较。
 * 匹配一律"包含且大小写不敏感"——四家平台的字段值都不稳定
 * （"本科" / "本科及以上" / " 本科 "都会出现），精确等于会把规则全打飞。
 *
 * 字段走 {@link JobCard} 接口而不是各平台的卡片类，所以四个平台共用一套规则：
 * 用户在 Boss 上调好的规则可以直接搬到猎聘/51job/智联。采不到的字段是 null，
 * 对应规则组自动跳过，不会误加分。
 */
public class JobScorer {

    private static final int REJECT_SCORE = -1000;

    private final ScoreRules rules;

    public JobScorer(ScoreRules rules) {
        this.rules = rules != null ? rules : new ScoreRules();
    }

    public ScoreResult score(JobCard card) {
        List<String> hits = new ArrayList<>();

        String title = lower(card.getJobName());
        String degree = lower(card.getJobDegree());
        String experience = lower(card.getJobExperience());
        String industry = lower(card.getIndustryName());
        String jd = lower(card.getPostDescription());

        String veto = firstHit(rules.getTitleReject(), title);
        if (veto != null) {
            return new ScoreResult(REJECT_SCORE, false, "职位黑名单命中: " + veto);
        }
        veto = firstHit(rules.getDegreeReject(), degree);
        if (veto != null) {
            return new ScoreResult(REJECT_SCORE, false, "学历黑名单命中: " + veto);
        }

        int score = 0;
        score += apply(rules.getJobRules(), title, "职位", hits);
        score += apply(rules.getDegreeRules(), degree, "学历", hits);
        score += apply(rules.getExperienceRules(), experience, "经验", hits);
        score += apply(rules.getIndustryRules(), industry, "行业", hits);
        score += apply(rules.getJdRules(), jd, "JD", hits);

        boolean pass = score >= rules.getThreshold();
        String reason = hits.isEmpty() ? "无命中规则" : String.join("; ", hits);
        return new ScoreResult(score, pass, reason);
    }

    private static int apply(List<ScoreRules.Rule> ruleList, String fieldValue, String group, List<String> hits) {
        if (ruleList == null || ruleList.isEmpty() || fieldValue == null) {
            return 0;
        }
        int total = 0;
        for (ScoreRules.Rule rule : ruleList) {
            if (rule.getMatch() == null || rule.getMatch().isBlank()) {
                continue;
            }
            if (fieldValue.contains(rule.getMatch().toLowerCase())) {
                total += rule.getScore();
                hits.add(group + "[" + rule.getMatch() + "]" + (rule.getScore() >= 0 ? "+" : "") + rule.getScore());
            }
        }
        return total;
    }

    private static String firstHit(List<String> patterns, String fieldValue) {
        if (patterns == null || fieldValue == null) {
            return null;
        }
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank() && fieldValue.contains(pattern.toLowerCase())) {
                return pattern;
            }
        }
        return null;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase();
    }
}
