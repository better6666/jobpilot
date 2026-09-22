package com.jobpilot.shixiseng;

import com.jobpilot.delivery.JobCard;
import lombok.Data;

/**
 * 实习僧岗位卡片。
 *
 * <p>实习僧的列表页信息量比另四个平台都小：只有「岗位名 / 日薪 / 城市 /
 * 每周几天 / 几个月 / 公司名 / 行业」七样，没有经验、学历、HR、规模。
 * JD 在详情页，要点了卡片才拿得到——所以 {@code postDescription} 由
 * {@link ShixisengDriver} 开详情页后回填，列表阶段是 null。
 *
 * <p>岗位名里混着 icon-font 的私用区字符（{@code \uf57f} 之类），
 * 显示和打分都会受影响，解析时必须剥掉（见 {@link #cleanText}）。
 */
@Data
public class ShixisengJobCard implements JobCard {

    private String jobId;
    private String jobName;
    private String salaryDesc;
    private String cityName;
    /** 学历。列表页没有，详情页的 .job_academic 才有，由驱动层补 */
    private String jobDegree;
    private String brandName;
    private String industryName;
    private String jobUrl;
    private String postDescription;

    /** 每周几天，如"4天/周" */
    private String weekDays;

    /** 实习月数，如"3个月" */
    private String monthDuration;

    /** 实习僧没有 HR 账号体系，去重键的这一半只能是 null */
    @Override
    public String getBossId() {
        return null;
    }

    @Override
    public String getAreaDistrict() {
        return null;
    }

    @Override
    public String getJobExperience() {
        return null;
    }

    @Override
    public String getBossName() {
        return null;
    }

    @Override
    public String getBossTitle() {
        return null;
    }

    @Override
    public String getBrandScaleName() {
        return null;
    }

    @Override
    public String getBossActiveTimeDesc() {
        // 实习僧没有"HR 活跃度"这个概念
        return null;
    }

    // ------------------------------------------------------------------
    // 纯函数：链接里提 jobId、剥 icon-font（可单测，不碰浏览器）
    // ------------------------------------------------------------------

    /**
     * 从详情链接提 jobId。
     * <p>
     * 形如 {@code https://www.shixiseng.com/intern/inn_tdauajw2bkdb?pcm=pc_SearchList}，
     * jobId 就是 {@code /intern/} 之后那一段。列表项的 {@code data-intern-id}
     * 属性里也有同一个值，两个来源任取其一。
     */
    public static String extractJobId(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.trim();
        int i = u.indexOf("/intern/");
        if (i < 0) {
            return null;
        }
        String rest = u.substring(i + "/intern/".length());
        int q = rest.indexOf('?');
        if (q >= 0) {
            rest = rest.substring(0, q);
        }
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        return rest.isBlank() ? null : rest;
    }

    /**
     * 剥掉 icon-font 的私用区字符再收尾。
     *
     * <p>实习僧把图标当字体用，直接混在文本里：岗位名可能是
     * {@code "\uf57f\ue477实习\uf148"}，日薪可能是 {@code "\uf5b9\uf475-\ue0f5\uf35b\uf475/天"}。
     * 不剥的话打分规则匹配不上、页面上也是一串方块。
     * Unicode 私用区是 U+E000–U+F8FF，用区间一次清掉。
     */
    public static String cleanText(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("[\\ue000-\\uf8ff]", "");
        s = s.replaceAll("\\s+", " ").trim();
        return s.isBlank() ? null : s;
    }
}
