package com.jobpilot.lagou;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.delivery.JobCard;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 拉勾岗位卡片，来自搜索页 {@code script#__NEXT_DATA__} 的
 * {@code props.pageProps.initData.content.positionResult.result}（2026-10-17 实测，每页 15 条）。
 * 字段齐全：学历、经验、区县、规模、行业、HR（publisherId），连完整 JD（positionDetail）都在，
 * 打分规则的所有分组在这个平台都能命中。
 */
@Data
public class LagouJobCard implements JobCard {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String jobId;
    private String bossId;
    private String jobName;
    private String cityName;
    private String areaDistrict;
    private String salaryDesc;
    private String jobExperience;
    private String jobDegree;
    private String brandName;
    private String industryName;
    private String brandScaleName;
    private String postDescription;
    private String jobUrl;

    @Override
    public String getBossName() {
        return null;
    }

    @Override
    public String getBossTitle() {
        return null;
    }

    @Override
    public String getBossActiveTimeDesc() {
        return null;
    }

    /** 整个 __NEXT_DATA__ JSON → 卡片列表 + 总数 */
    record Page(List<LagouJobCard> cards, int total) {
    }

    static Page parse(String nextDataJson) {
        List<LagouJobCard> cards = new ArrayList<>();
        int total = 0;
        if (nextDataJson == null || nextDataJson.isBlank()) {
            return new Page(cards, 0);
        }
        try {
            JsonNode pr = MAPPER.readTree(nextDataJson).path("props").path("pageProps")
                    .path("initData").path("content").path("positionResult");
            total = pr.path("totalCount").asInt(0);
            for (JsonNode j : pr.path("result")) {
                LagouJobCard c = from(j);
                if (c != null) {
                    cards.add(c);
                }
            }
        } catch (Exception ignore) {
            // 结构对不上就当这一页没数据
        }
        return new Page(cards, total);
    }

    static LagouJobCard from(JsonNode j) {
        String id = text(j, "positionId");
        if (id == null || "0".equals(id)) {
            return null;
        }
        LagouJobCard c = new LagouJobCard();
        c.setJobId(id);
        c.setBossId(text(j, "publisherId"));
        c.setJobName(text(j, "positionName"));
        c.setCityName(text(j, "city"));
        c.setAreaDistrict(text(j, "district"));
        c.setSalaryDesc(text(j, "salary"));
        c.setJobExperience(text(j, "workYear"));
        c.setJobDegree(text(j, "education"));
        String full = text(j, "companyFullName");
        c.setBrandName(full != null ? full : text(j, "companyShortName"));
        c.setIndustryName(text(j, "industryField"));
        c.setBrandScaleName(text(j, "companySize"));
        c.setPostDescription(plain(text(j, "positionDetail")));
        c.setJobUrl("https://www.lagou.com/wn/jobs/" + id + ".html");
        return c;
    }

    /** JD 里混着 <br /> 和别的标签，打分只要纯文字 */
    static String plain(String html) {
        if (html == null) {
            return null;
        }
        String s = html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ").replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n").trim();
        return s.isEmpty() ? null : s;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }
}
