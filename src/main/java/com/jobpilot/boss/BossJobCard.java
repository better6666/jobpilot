package com.jobpilot.boss;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.delivery.JobCard;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 岗位卡片。数据来自列表页点击卡片时拦截的 /wapi/zpgeek/job/detail.json 响应，
 * 不解析 DOM——列表页 DOM 是虚拟滚动的，字段残缺且会变。
 *
 * 实现 {@link JobCard} 是为了让编排层（DeliveryService）只认平台无关的那套
 * getter；Boss 自己的字段名（encryptId/encryptUserId）原样保留，数据库里的
 * 列名和它们是对得上的。
 */
@Data
public class BossJobCard implements JobCard {

    private String encryptId;
    private String encryptUserId;
    private String jobName;
    private String salaryDesc;
    private String locationName;
    private String areaDistrict;
    private String experienceName;
    private String degreeName;
    private String postDescription;
    private String address;
    private String brandName;
    private String industryName;
    private String brandScaleName;
    private String bossName;
    private String bossTitle;
    private String bossActiveTimeDesc;
    private List<String> jobLabels = new ArrayList<>();
    private List<String> skills = new ArrayList<>();
    private List<String> welfare = new ArrayList<>();

    /** 详情页地址。Boss 的加密 id 同时是详情页 URL 的 securityId */
    public String getJobUrl() {
        return encryptId == null ? null : "https://www.zhipin.com/job_detail/" + encryptId + ".html";
    }

    /** {@inheritDoc} Boss 的岗位唯一标识就是加密 id */
    @Override
    public String getJobId() {
        return encryptId;
    }

    /** {@inheritDoc} */
    @Override
    public String getBossId() {
        return encryptUserId;
    }

    /** {@inheritDoc} Boss 的字段叫 locationName */
    @Override
    public String getCityName() {
        return locationName;
    }

    /** {@inheritDoc} Boss 的字段叫 experienceName */
    @Override
    public String getJobExperience() {
        return experienceName;
    }

    /** {@inheritDoc} Boss 的字段叫 degreeName */
    @Override
    public String getJobDegree() {
        return degreeName;
    }

    /**
     * 从 detail.json 响应体解析。结构：
     * {"zpData": {"jobInfo": {...}, "brandComInfo": {...}, "bossInfo": {...}}}
     * 任何一层缺失都返回 null（该卡片没有数据，调用方跳过）。
     */
    public static BossJobCard parse(String responseBody) {
        try {
            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody);
            JsonNode zpData = root.path("zpData");
            JsonNode jobInfo = zpData.path("jobInfo");
            if (jobInfo.isMissingNode() || jobInfo.isObject() && jobInfo.isEmpty()) {
                return null;
            }
            BossJobCard card = new BossJobCard();
            card.encryptId = text(jobInfo, "encryptId");
            card.encryptUserId = text(jobInfo, "encryptUserId");
            card.jobName = text(jobInfo, "jobName");
            card.salaryDesc = text(jobInfo, "salaryDesc");
            card.locationName = text(jobInfo, "locationName");
            card.areaDistrict = text(jobInfo, "areaDistrict");
            card.experienceName = text(jobInfo, "experienceName");
            card.degreeName = text(jobInfo, "degreeName");
            card.postDescription = text(jobInfo, "postDescription");
            card.address = text(jobInfo, "address");
            card.jobLabels = textList(jobInfo, "jobLabels");
            card.skills = textList(jobInfo, "skills");
            card.welfare = textList(jobInfo, "welfareList");

            JsonNode brand = zpData.path("brandComInfo");
            card.brandName = text(brand, "brandName");
            card.industryName = text(brand, "industryName");
            card.brandScaleName = text(brand, "brandScaleName");

            JsonNode boss = zpData.path("bossInfo");
            card.bossName = text(boss, "name");
            card.bossTitle = text(boss, "title");
            card.bossActiveTimeDesc = text(boss, "activeTimeDesc");
            if (card.encryptUserId == null) {
                // 部分岗位的 HR id 落在 bossInfo 里
                card.encryptUserId = text(boss, "encryptUserId");
                if (card.encryptUserId == null) {
                    card.encryptUserId = text(boss, "encryptBossId");
                }
            }
            return card;
        } catch (Exception e) {
            return null;
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

    private static List<String> textList(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        JsonNode array = node.path(field);
        if (array.isArray()) {
            array.forEach(item -> {
                String s = item.isTextual() ? item.asText() : item.path("name").asText(null);
                if (s != null && !s.isBlank()) {
                    result.add(s);
                }
            });
        }
        return result;
    }
}
