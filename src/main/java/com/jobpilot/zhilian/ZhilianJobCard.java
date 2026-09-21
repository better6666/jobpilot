package com.jobpilot.zhilian;

import com.jobpilot.delivery.JobCard;
import lombok.Data;

/**
 * 智联招聘岗位卡片。
 * <p>
 * 智联是四个平台里唯一<b>没有任何可用接口</b>的：搜索列表不走 XHR，
 * 老工程实测只能整页爬 DOM。所以这个卡片由 {@link ZhilianDriver} 从
 * {@code div.job-card} 里逐字段读出来，没有 JSON 解析这条路。
 * <p>
 * 采不到的字段（学历、HR、行业、规模、JD）一律留 null，交给打分规则
 * 自然不命中——比瞎猜一个值安全。
 */
@Data
public class ZhilianJobCard implements JobCard {

    private String jobId;
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

    /** 智联的列表接口不返回 HR 标识，去重键的这一半只能是 null */
    @Override
    public String getBossId() {
        return null;
    }

    @Override
    public String getBossActiveTimeDesc() {
        // 智联没有"HR 活跃度"这个概念
        return null;
    }

    // ------------------------------------------------------------------
    // 纯函数：链接里提 jobId（可单测，不碰浏览器）
    // ------------------------------------------------------------------

    /**
     * 从职位详情链接提 jobId。
     * <p>
     * 形如 {@code https://jobs.zhaopin.com/.../jobdetail/1234567.htm}。
     * 取 {@code jobdetail/} 之后、最后一个 {@code .htm} 之前那一段。
     * <p>
     * 注意老工程是从<b>公司名链接</b>提的 jobId——公司链接指向公司主页，
     * 里面根本没有 jobdetail 段，那是个一直没触发的兜底分支。这里优先从
     * 职位标题链接提，公司链接只作最后兜底。
     */
    public static String extractJobId(String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        int start = link.indexOf("jobdetail/");
        int end = link.lastIndexOf(".htm");
        if (start >= 0 && end > start) {
            String id = link.substring(start + "jobdetail/".length(), end).trim();
            return id.isBlank() ? null : id;
        }
        return null;
    }

    /**
     * "苏州·常熟市" / "苏州-姑苏区" / "苏州 工业园区" → city=苏州, district=剩下那段。
     * <p>
     * 智联的地区串实测三种写法都有：{@code ul.job-detail-summary__tags} 第一个
     * {@code li} 给的是"苏州·常熟市"（间隔号），列表卡片给的是"苏州 常熟 常福"
     * （空格），老页面还有中横线。取最先出现的那个分隔符切。
     * <p>
     * 只有一段（"苏州"）时 district 留空；空串不拆。
     */
    static void splitLocation(ZhilianJobCard card) {
        String raw = card.getLocationRaw();
        if (raw == null || raw.isBlank()) {
            return;
        }
        String trimmed = raw.trim();
        int cut = -1;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '-' || c == '·' || c == '•' || c == '.' || Character.isWhitespace(c)) {
                cut = i;
                break;
            }
        }
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
}
