package com.jobpilot.boss;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 打分规则。整体设计：基准分 0，命中的规则按 score 加减，低于 threshold 不投递；
 * 黑名单类规则（titleReject/degreeReject）直接一票否决。
 *
 * 规则语义与旧工具一致，用户在原工程里调好的规则可以原样搬过来。
 * 出厂默认全部为空、threshold=0：不拦任何岗位，用户按自己的求职方向在管理页里配。
 */
@Data
public class ScoreRules {

    /** 分数阈值，达到才投递 */
    private int threshold = 0;

    /** 职位名包含任一条 → 一票否决 */
    private List<String> titleReject = new ArrayList<>();

    /** 学历包含任一条 → 一票否决 */
    private List<String> degreeReject = new ArrayList<>();

    /** 职位名匹配规则（在职位名里找 match） */
    private List<Rule> jobRules = new ArrayList<>();

    /** 学历匹配规则（在学历字段里找 match） */
    private List<Rule> degreeRules = new ArrayList<>();

    /** 经验匹配规则（在经验字段里找 match） */
    private List<Rule> experienceRules = new ArrayList<>();

    /** 行业匹配规则（在公司行业里找 match） */
    private List<Rule> industryRules = new ArrayList<>();

    /** 岗位描述匹配规则（在 JD 全文里找 match） */
    private List<Rule> jdRules = new ArrayList<>();

    @Data
    public static class Rule {
        /** 匹配词，包含即命中，大小写不敏感 */
        private String match;
        /** 命中后加减的分数 */
        private int score;
    }
}
