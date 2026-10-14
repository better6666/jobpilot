package com.jobpilot.yingjiesheng;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.delivery.JobCard;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 应届生求职网岗位卡片，来自搜索接口
 * {@code youngapi.yingjiesheng.com/open/noauth/job/search} 的 JSON。
 *
 * <p>应届生是前程无忧旗下的，接口字段和 51job 一脉相承（{@code jobid / providesalary /
 * coname / cosize}），城市码也是 51job 那套六位码。比 51job 多的是 HR 名字和
 * 「2分钟前回复」这种活跃度，少的是 JD——列表接口里 {@code jobDescribe} 恒为空串。
 *
 * <p>{@code isReprintJob=1} 的是转载岗位，申请会跳到原站，投不了。
 */
@Data
public class YingjieshengJobCard implements JobCard {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JOB_ID = Pattern.compile("/jobdetail/(\\d+)\\.html|[?&]jobid=(\\d+)");

    private String jobId;
    private String recruiterId;
    private String jobName;
    private String salaryDesc;
    private String cityName;
    private String areaDistrict;
    private String jobExperience;
    private String jobDegree;
    private String brandName;
    private String industryName;
    private String brandScaleName;
    private String bossName;
    private String bossTitle;
    private String bossActiveTimeDesc;
    private String jobUrl;
    private String postDescription;
    /** 转载岗位：申请跳原站 */
    private boolean reprint;
    /** 接口自己说已经申请过（isapply=1） */
    private boolean applied;

    @Override
    public String getBossId() {
        return recruiterId;
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    public static List<YingjieshengJobCard> parseList(String body) {
        List<YingjieshengJobCard> cards = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return cards;
        }
        try {
            JsonNode items = MAPPER.readTree(body).path("resultbody").path("searchData").path("joblist").path("items");
            for (JsonNode item : items) {
                YingjieshengJobCard card = parse(item);
                if (card != null) {
                    cards.add(card);
                }
            }
        } catch (Exception ignore) {
            // 单次响应解析不了就当没拦到，DOM 那侧还能兜底出岗位名
        }
        return cards;
    }

    static YingjieshengJobCard parse(JsonNode item) {
        String jobId = text(item, "jobid");
        if (jobId == null || "0".equals(jobId)) {
            return null;
        }
        YingjieshengJobCard card = new YingjieshengJobCard();
        card.setJobId(jobId);
        card.setRecruiterId(text(item, "hrUid"));
        card.setJobName(text(item, "jobname"));
        card.setSalaryDesc(text(item, "providesalary"));
        splitArea(card, text(item, "jobarea"));
        card.setJobExperience(text(item, "workyear"));
        card.setJobDegree(text(item, "degree"));
        card.setBrandName(text(item, "coname"));
        String industry = text(item, "coIndustryText");
        card.setIndustryName(industry != null ? industry : text(item, "indtype"));
        card.setBrandScaleName(text(item, "cosize"));
        card.setBossName(text(item, "hrName"));
        card.setBossTitle(text(item, "hrPosition"));
        card.setBossActiveTimeDesc(text(item, "hrActiveStatus"));
        card.setPostDescription(text(item, "jobDescribe"));
        card.setJobUrl("https://q.yingjiesheng.com/jobdetail/" + jobId + ".html");
        card.setReprint("1".equals(text(item, "isReprintJob")) || text(item, "reprintFromUrl") != null);
        card.setApplied("1".equals(text(item, "isapply")));
        return card;
    }

    /** "哈尔滨-南岗区" → 城市 + 区；"北京" 只有城市 */
    static void splitArea(YingjieshengJobCard card, String area) {
        if (area == null) {
            return;
        }
        int dash = area.indexOf('-');
        if (dash > 0) {
            card.setCityName(area.substring(0, dash).trim());
            String district = area.substring(dash + 1).trim();
            card.setAreaDistrict(district.isEmpty() ? null : district);
        } else {
            card.setCityName(area.trim());
        }
    }

    public static String extractJobId(String href) {
        if (href == null) {
            return null;
        }
        Matcher m = JOB_ID.matcher(href);
        if (!m.find()) {
            return null;
        }
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }
}
