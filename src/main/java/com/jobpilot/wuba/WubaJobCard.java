package com.jobpilot.wuba;

import com.jobpilot.delivery.JobCard;
import lombok.Data;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 58 同城岗位卡片，读自列表页 {@code li.job_item}（2026-10-17 登录后实测结构）：
 * <pre>
 *   .job_name a          "杨舍 | 文化传媒公司运营客服3k起"（竖线前是区域）
 *   .job_salary          "3000-5000元/月"
 *   .comp_name a[title]  公司全名
 *   .job_require         .cate 职类 | .xueli 学历 | .jingyan 经验
 *   a.apply[infoid]      "申请" 按钮，infoid 就是岗位 ID
 *   i[data-zpim]         URL 编码的 JSON，里面有 HR 的 userid
 * </pre>
 * 列表上没有 JD，{@code jdRules} 在这个平台不会命中。
 */
@Data
public class WubaJobCard implements JobCard {

    private static final Pattern USER_ID = Pattern.compile("\"userid\"\\s*:\\s*\"(\\d+)\"");

    private String jobId;
    private String bossId;
    private String jobName;
    private String areaDistrict;
    private String cityName;
    private String salaryDesc;
    private String brandName;
    /** 职类，如"客服专员/助理"。不是行业，但打分规则里按职位名一组用 */
    private String category;
    private String jobDegree;
    private String jobExperience;
    private String jobUrl;
    private String postDescription;
    /** 按钮已经是"已申请" */
    private boolean applied;

    @Override
    public String getIndustryName() {
        return category;
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
        return null;
    }

    /** 从列表项读出来的那一包字段拼卡片。拆出来脱离浏览器单测 */
    static WubaJobCard fromFields(Map<String, Object> f, String cityName) {
        String id = clean((String) f.get("infoid"));
        if (id == null) {
            return null;
        }
        WubaJobCard c = new WubaJobCard();
        c.setJobId(id);
        c.setCityName(cityName);
        String title = clean((String) f.get("title"));
        if (title != null) {
            int bar = title.indexOf('|');
            if (bar > 0 && bar < 12) {
                c.setAreaDistrict(clean(title.substring(0, bar)));
                c.setJobName(clean(title.substring(bar + 1)));
            } else {
                c.setJobName(title);
            }
        }
        c.setSalaryDesc(clean((String) f.get("salary")));
        c.setBrandName(clean((String) f.get("company")));
        c.setCategory(clean((String) f.get("cate")));
        c.setJobDegree(clean((String) f.get("xueli")));
        c.setJobExperience(clean((String) f.get("jingyan")));
        String href = clean((String) f.get("href"));
        if (href != null) {
            int q = href.indexOf('?');
            c.setJobUrl(q > 0 ? href.substring(0, q) : href);
        }
        c.setBossId(userIdFrom((String) f.get("zpim")));
        String btn = clean((String) f.get("btn"));
        c.setApplied(btn != null && (btn.contains("已申请") || btn.contains("已投递")));
        return c;
    }

    static String userIdFrom(String zpim) {
        if (zpim == null || zpim.isBlank()) {
            return null;
        }
        try {
            Matcher m = USER_ID.matcher(URLDecoder.decode(zpim, StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? null : t;
    }
}
