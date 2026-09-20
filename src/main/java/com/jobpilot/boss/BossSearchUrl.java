package com.jobpilot.boss;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Boss 搜索页 URL 构造。
 * <p>
 * 形如 https://www.zhipin.com/web/geek/jobs?city=101020100&query=%E9%99%88...
 * query 是关键词；其余是筛选项，空值一律不带（带上空值 Boss 会当成"不限"，
 * 但会让落地 URL 和配置对不上，排查时误判）。
 */
public final class BossSearchUrl {

    private static final String BASE = "https://www.zhipin.com/web/geek/jobs";

    private BossSearchUrl() {
    }

    public static String build(String cityCode, String keyword, BossProperties.BossConfig config) {
        List<String> params = new ArrayList<>();
        addParam(params, "city", cityCode);
        addParam(params, "query", keyword);
        if (config != null) {
            addParam(params, "jobType", config.getJobType());
            addParam(params, "salary", config.getSalary());
            addParam(params, "experience", config.getExperience());
            addParam(params, "degree", config.getDegree());
            addParam(params, "scale", config.getScale());
            addParam(params, "industry", config.getIndustry());
            addParam(params, "stage", config.getStage());
        }
        if (params.isEmpty()) {
            return BASE;
        }
        return BASE + "?" + String.join("&", params);
    }

    /** 城市码：已经是 9 位数字码原样返回；否则按城市名查码表；查不到返回 null */
    public static String resolveCityCode(BossOptions options, String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        String trimmed = city.trim();
        if (trimmed.matches("\\d{6,9}")) {
            return trimmed;
        }
        return options.cityCode(trimmed);
    }

    private static void addParam(List<String> params, String key, String value) {
        if (value == null || value.isBlank() || "0".equals(value.trim())) {
            return;
        }
        params.add(key + "=" + encode(value.trim()));
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // UTF-8 是 JVM 必须支持的字符集，永远走不到
            return value;
        }
    }
}
