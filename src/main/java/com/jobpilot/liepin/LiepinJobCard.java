package com.jobpilot.liepin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.delivery.JobCard;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 猎聘岗位卡片。数据来自列表页拦截的搜索接口响应，不解析 DOM。
 *
 * 接口每个卡片分三个子对象：job（职位）/ comp（公司）/ recruiter（HR）。
 * 这里把它们拍平成 JobCard 那一套字段——猎聘的字段名和 Boss 完全不是一套，
 * 但落到 deliveries 表里是同一批列。
 */
@Data
public class LiepinJobCard implements JobCard {

    private String jobId;
    private String recruiterId;
    private String jobName;
    private String salaryDesc;
    /** job.dq 整体，形如"苏州-姑苏区" */
    private String locationRaw;
    private String cityName;
    private String areaDistrict;
    private String jobExperience;
    private String jobDegree;
    private String brandName;
    private String industryName;
    private String brandScaleName;
    private String bossName;
    private String bossTitle;
    private String jobUrl;
    /** 接口给的刷新时间，形如 20241210175950；仅用于日志排查 */
    private String refreshTime;

    // ------------------------------------------------------------------
    // JobCard 接口
    // ------------------------------------------------------------------

    @Override
    public String getJobId() {
        return jobId;
    }

    @Override
    public String getBossId() {
        return recruiterId;
    }

    @Override
    public String getPostDescription() {
        // 猎聘搜索接口不给 JD 全文，详情页才有；采集阶段不打开详情页
        return null;
    }

    @Override
    public String getBossActiveTimeDesc() {
        // 猎聘卡片没有 HR 活跃度这个概念
        return null;
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 解析搜索接口响应体，返回本页全部卡片。
     *
     * 结构：{"data": {"data": {"jobCardList": [ {job, comp, recruiter}, ... ]}}}
     * 两层 data 是老工程的实测兜底（有的版本只有一层），任一不是数组就整体丢弃。
     */
    public static List<LiepinJobCard> parseList(String responseBody) {
        List<LiepinJobCard> cards = new ArrayList<>();
        try {
            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody);
            JsonNode list = root.path("data").path("data").path("jobCardList");
            if (!list.isArray()) {
                list = root.path("data").path("jobCardList");
            }
            if (!list.isArray()) {
                return cards;
            }
            for (JsonNode node : list) {
                LiepinJobCard card = parse(node);
                if (card != null) {
                    cards.add(card);
                }
            }
        } catch (Exception e) {
            // 响应体不是 JSON（验证页等）：本页没数据，调用方按空列表处理
        }
        return cards;
    }

    /** 解析单个卡片。jobId 缺失或为 0 的卡片没有意义，返回 null 让调用方跳过 */
    public static LiepinJobCard parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode job = node.path("job");
        JsonNode comp = node.path("comp");
        JsonNode recruiter = node.path("recruiter");

        String jobId = text(job, "jobId");
        if (jobId == null || "0".equals(jobId)) {
            return null;
        }
        LiepinJobCard card = new LiepinJobCard();
        card.jobId = jobId;
        card.jobName = text(job, "title");
        card.salaryDesc = text(job, "salary");
        card.locationRaw = text(job, "dq");
        splitLocation(card);
        card.jobExperience = text(job, "requireWorkYears");
        card.jobDegree = text(job, "requireEduLevel");
        card.jobUrl = text(job, "link");
        card.refreshTime = text(job, "refreshTime");

        card.brandName = text(comp, "compName");
        card.industryName = text(comp, "compIndustry");
        card.brandScaleName = text(comp, "compScale");

        card.bossName = text(recruiter, "recruiterName");
        card.bossTitle = text(recruiter, "recruiterTitle");
        card.recruiterId = text(recruiter, "recruiterId");
        if (card.recruiterId == null) {
            // 部分卡片 recruiterId 缺失，imId 是同一人的另一个标识
            card.recruiterId = text(recruiter, "imId");
        }
        return card;
    }

    /**
     * job.dq 形如"苏州-姑苏区"，拆成城市 + 区县两列。
     * 没有中横线（全国岗）时整体当城市，区县留空。
     */
    static void splitLocation(LiepinJobCard card) {
        String raw = card.locationRaw;
        if (raw == null || raw.isBlank()) {
            return;
        }
        int dash = raw.indexOf('-');
        if (dash > 0 && dash < raw.length() - 1) {
            card.cityName = raw.substring(0, dash).trim();
            card.areaDistrict = raw.substring(dash + 1).trim();
        } else {
            card.cityName = raw.trim();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String s = value.asText();
        return (s == null || s.isBlank()) ? null : s;
    }
}
