package com.jobpilot.nowcoder;

import com.jobpilot.delivery.JobCard;
import lombok.Data;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 牛客岗位卡片。
 *
 * <p>列表页（{@code .job-card-item}，2026-10-14 实测）一张卡上有：
 * 「校招 | 岗位名」、薪资、一排标签（HR 动态 / 城市 / 届别或年限 / 学历 / 职能），
 * 公司名、行业、规模。<b>标签的顺序和个数都不固定</b>——有的卡没有 HR 动态，
 * 有的多出"有转正""5天/周"——所以不能按下标取，只能按长相认（见 {@link #applyTags}）。
 *
 * <p>JD 和 HR 名字只在详情页，由 {@link NowcoderDriver} 开详情后回填。
 */
@Data
public class NowcoderJobCard implements JobCard {

    private static final Pattern JOB_ID = Pattern.compile("/jobs/detail/(\\d+)");
    private static final Pattern DEGREE = Pattern.compile("^(博士|硕士|研究生|本科|大专|专科|高中|中专|学历不限)$");
    /** "2027届""毕业不限""1-3年""经验不限""在校/应届" */
    private static final Pattern EXPERIENCE = Pattern.compile("(\\d{4}届|毕业不限|\\d+-\\d+年|\\d+年以[上下]|经验不限|应届|在校)");
    private static final Pattern INTERN_ONLY = Pattern.compile("(\\d天/周|最少\\d+个月|有转正)");

    private String jobId;
    /** 校招 / 实习 / 社招，来自岗位名前缀「校招 | 」 */
    private String recruitType;
    private String jobName;
    private String salaryDesc;
    private String cityName;
    private String jobExperience;
    private String jobDegree;
    private String brandName;
    private String industryName;
    private String brandScaleName;
    /** "HR刚处理简历""HR今日在线"这类 */
    private String bossActiveTimeDesc;
    private String bossName;
    private String bossTitle;
    private String jobUrl;
    private String postDescription;
    /**
     * 「简历直投官网」「直达官网」：点申请会跳到企业自己的招聘站，
     * 在那边要重新注册填表，脚本投不了，打分阶段直接拦下
     */
    private boolean external;

    /** 牛客的卡片上没有 HR 账号标识 */
    @Override
    public String getBossId() {
        return null;
    }

    @Override
    public String getAreaDistrict() {
        return null;
    }

    // ------------------------------------------------------------------
    // 纯函数（不碰浏览器，单测直接喂真实页面上读出来的字符串）
    // ------------------------------------------------------------------

    public static String extractJobId(String href) {
        if (href == null) {
            return null;
        }
        Matcher m = JOB_ID.matcher(href);
        return m.find() ? m.group(1) : null;
    }

    /** 「校招 | 校招-内容运营（短视频）」→ 类型「校招」+ 名字「校招-内容运营（短视频）」 */
    public void applyTitle(String raw) {
        String title = clean(raw);
        if (title == null) {
            return;
        }
        int bar = title.indexOf('|');
        if (bar > 0 && bar <= 4) {
            recruitType = clean(title.substring(0, bar));
            jobName = clean(title.substring(bar + 1));
        } else {
            jobName = title;
        }
    }

    /** 按长相把一排标签分到各字段。认不出的第一个当城市（城市没有固定特征，排在 HR 动态后面） */
    public void applyTags(List<String> tags) {
        if (tags == null) {
            return;
        }
        for (String raw : tags) {
            String tag = clean(raw);
            if (tag == null) {
                continue;
            }
            if (tag.startsWith("HR") || tag.contains("在线") || tag.contains("处理简历")) {
                bossActiveTimeDesc = tag;
            } else if (DEGREE.matcher(tag).matches()) {
                jobDegree = tag;
            } else if (EXPERIENCE.matcher(tag).find() && jobExperience == null) {
                jobExperience = tag;
            } else if (INTERN_ONLY.matcher(tag).find()) {
                // 实习的"5天/周 最少3个月 有转正"：对打分没用，也不该被当成城市
                continue;
            } else if (cityName == null) {
                cityName = tag;
            }
        }
    }

    /** 公司那一栏的「行业 | 规模」 */
    public void applyCompanyInfo(List<String> infos) {
        if (infos == null) {
            return;
        }
        for (String raw : infos) {
            String info = clean(raw);
            if (info == null) {
                continue;
            }
            if (info.endsWith("人") || info.contains("人以")) {
                brandScaleName = info;
            } else if (industryName == null) {
                industryName = info;
            }
        }
    }

    /** 卡片整段文字里带官网直投的标记 */
    public static boolean isExternalText(String cardText) {
        return cardText != null && (cardText.contains("直投官网") || cardText.contains("直达官网"));
    }

    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s;
    }
}
