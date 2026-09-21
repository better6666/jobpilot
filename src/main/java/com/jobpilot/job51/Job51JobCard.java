package com.jobpilot.job51;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.delivery.JobCard;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 51job 岗位卡片，来自搜索接口 {@code /api/job/search-pc} 的 JSON。
 *
 * 字段名带多个候选是必须的：51job 的接口在不同入口（we.51job.com 的 PC 搜索、
 * 移动端同源接口）返回的键名不一致，老工程就是靠这套候选名兼容过来的。
 * 新名字按可能性从高到低排，取第一个非空的。
 *
 * 与猎聘的差别：51job 的 {@code jobAreaString} 形如"苏州-姑苏区"，
 * 和猎聘一样要拆成城市 + 区。
 */
@Data
public class Job51JobCard implements JobCard {

    private String jobId;
    private String recruiterId;
    private String jobName;
    private String salaryDesc;
    /** 拆分前的原始地区串，如"苏州-姑苏区" */
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
    private String postDescription;

    /** 已投递状态来自按钮文本，不在接口里；这里只做数据承载 */
    @Override
    public String getBossId() {
        return recruiterId;
    }

    @Override
    public String getBossActiveTimeDesc() {
        // 51job 没有"HR 活跃度"这个概念
        return null;
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /** 列表路径按序探测：接口改版时换过几次外层包裹，取第一个是数组的 */
    public static List<Job51JobCard> parseList(String responseBody) {
        List<Job51JobCard> cards = new ArrayList<>();
        if (responseBody == null || responseBody.isBlank()) {
            return cards;
        }
        try {
            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody);
            JsonNode list = firstArray(root,
                    "data.items", "data.jobList", "data.list", "data.jobs",
                    "resultbody.job.items", "job.items", "resultbody.items");
            if (list == null) {
                return cards;
            }
            for (JsonNode item : list) {
                Job51JobCard card = parse(item);
                if (card != null) {
                    cards.add(card);
                }
            }
        } catch (Exception e) {
            // 单次响应解析失败不该中断整个关键词，交由 DOM 兜底
        }
        return cards;
    }

    /** jobId 缺失或为 0 的实体没有意义（去重键的一半就是它），直接丢 */
    public static Job51JobCard parse(JsonNode item) {
        if (item == null || !item.isObject()) {
            return null;
        }
        String jobId = text(item, "jobId", "jobid", "id");
        if (jobId == null || jobId.isBlank() || "0".equals(jobId)) {
            return null;
        }
        Job51JobCard card = new Job51JobCard();
        card.setJobId(jobId);
        card.setRecruiterId(text(item, "hrUid", "recruiterId", "hrId"));
        card.setJobName(text(item, "jobName", "jobTitle", "title"));
        card.setSalaryDesc(text(item, "provideSalaryString", "salaryDesc", "salary", "salaryText"));
        card.setLocationRaw(text(item, "jobAreaString", "jobArea", "cityName"));
        splitLocation(card);
        card.setJobExperience(text(item, "workYearString", "workYear", "requireWorkYears"));
        card.setJobDegree(text(item, "degreeString", "degree", "requireEduLevel"));
        card.setBrandName(text(item, "fullCompanyName", "companyName", "ctmName"));
        card.setIndustryName(text(item, "industryType1Str", "industry", "compIndustry"));
        card.setBrandScaleName(text(item, "companySizeString", "companySize", "compScale"));
        card.setBossName(text(item, "hrName", "recruiterName"));
        card.setBossTitle(text(item, "hrPosition", "recruiterTitle"));
        card.setPostDescription(text(item, "jobDescribe", "jobDescription", "describe"));
        card.setJobUrl(resolveJobUrl(item, jobId));
        return card;
    }

    /** 优先用接口给的 jobHref；没有就按 jobId 拼 PC 详情页 */
    private static String resolveJobUrl(JsonNode item, String jobId) {
        String href = text(item, "jobHref", "jobUrl", "jobLink");
        if (href != null && !href.isBlank()) {
            return href;
        }
        return "https://we.51job.com/pc/jobdetail?jobId=" + jobId;
    }

    /**
     * "苏州-姑苏区" → city=苏州, district=姑苏区。
     * <p>
     * 分隔符两种都见过：{@code -}（苏州-姑苏区）和 {@code ·}（苏州·苏州工业园区），
     * 取最先出现的那个切。只有一段（"苏州"）时 district 留空；空串不拆。
     */
    static void splitLocation(Job51JobCard card) {
        String raw = card.getLocationRaw();
        if (raw == null || raw.isBlank()) {
            return;
        }
        String trimmed = raw.trim();
        int dash = trimmed.indexOf('-');
        int mid = trimmed.indexOf('·');
        int cut = dash < 0 ? mid : (mid < 0 ? dash : Math.min(dash, mid));
        if (cut < 0) {
            card.setCityName(trimmed);
            return;
        }
        String city = trimmed.substring(0, cut).trim();
        String district = trimmed.substring(cut + 1).trim();
        if (!city.isBlank()) {
            card.setCityName(city);
        }
        if (!district.isBlank()) {
            card.setAreaDistrict(district);
        }
    }

    private static JsonNode firstArray(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode node = root;
            for (String segment : path.split("\\.")) {
                node = node.path(segment);
            }
            if (node.isArray()) {
                return node;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isValueNode()) {
                String s = value.asText();
                if (s != null && !s.isBlank()) {
                    return s.trim();
                }
            }
        }
        return null;
    }
}
