package com.jobpilot.zhilian;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 智联招聘搜索页 URL 构造。
 * <p>
 * 形如 https://www.zhaopin.com/jobs?pageMode=search&jl=639&kw=陈列设计&sl=12000%2C20000
 * <p>
 * 三条硬约束（都来自实测，改之前先读）：
 * <ul>
 *   <li><b>关键词和薪资都必须走 query</b>。旧版先导航 {@code /sou/jl639/p1} 再往
 *       输入框敲关键词，SPA 会跳到不含 {@code sl} 的地址，薪资过滤被丢；
 *       直接拼 query 则是服务端渲染，两个条件都生效</li>
 *   <li>城市码是 query 参数 {@code jl}，不是路径段。码是智联自己的
 *       3 位内部码（北京=530），和 Boss/51job/猎聘都不同</li>
 *   <li>薪资 {@code sl} 是<b>原始金额区间</b>（"最低,最高"，单位元），
 *       不是码表里的档位码。逗号要 URL 编码成 %2C。注意智联的过滤是
 *       <b>区间重叠</b>不是包含：sl=12000,20000 会放行 8000-15000 这种</li>
 * </ul>
 */
public final class ZhilianSearchUrl {

    private ZhilianSearchUrl() {
    }

    /**
     * 构造指定页的搜索地址。
     * <p>
     * 走 {@code /jobs?jl=..&pageMode=search&kw=..&sl=..} 这条服务端渲染路由，
     * <b>关键词和薪资都必须拼在 query 上</b>。旧版走 {@code /sou/jl639/p1} 再往
     * 输入框敲关键词，SPA 会导航到不含 {@code sl} 的地址，薪资过滤被悄悄丢掉
     * （实测：同一关键词有/无 sl 分别返回 20 和 11 条，薪资档位完全不同）。
     *
     * @param cityCode 智联城市码；null/空 = 全国
     * @param keyword  搜索关键词，会 URL 编码进 query
     * @param config   取 salary
     * @param pageNum  页码，从 1 开始
     */
    public static String build(String cityCode, String keyword,
                               ZhilianProperties.ZhilianConfig config, int pageNum) {
        StringBuilder url = new StringBuilder("https://www.zhaopin.com/jobs?pageMode=search");
        if (cityCode != null && !cityCode.isBlank()) {
            url.append("&jl=").append(cityCode.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            url.append("&kw=").append(encode(keyword.trim()));
        }
        String salary = salaryRange(config);
        if (salary != null) {
            url.append("&sl=").append(encode(salary));
        }
        if (pageNum > 1) {
            url.append("&page=").append(pageNum);
        }
        return url.toString();
    }

    /** "12000, 20000" → "12000,20000"；空或"不限"返回 null */
    static String salaryRange(ZhilianProperties.ZhilianConfig config) {
        if (config == null || config.getSalary() == null) {
            return null;
        }
        String raw = config.getSalary().trim();
        if (raw.isBlank() || "不限".equals(raw)) {
            return null;
        }
        // 用户可能写成区间：8000-15000、8000~15000、1.2万-2万
        String normalized = raw.replaceAll("[\\-–—~～]", ",");
        List<String> values = new ArrayList<>();
        for (String part : normalized.split("[,，]")) {
            String digits = amount(part);
            if (digits.isBlank()) {
                continue;
            }
            values.add(digits);
        }
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() == 1) {
            return values.get(0);
        }
        return values.get(0) + "," + values.get(1);
    }

    /**
     * 单段金额 → 元的整数字符串。"1.2万"→12000，"12k"→12000，" 12000 "→12000；
     * 取不出数字返回空串。
     */
    private static String amount(String part) {
        String s = part.trim();
        if (s.isBlank()) {
            return "";
        }
        long unit = 1;
        if (s.indexOf('万') >= 0 || s.indexOf('w') >= 0 || s.indexOf('W') >= 0) {
            unit = 10_000;
        } else if (s.indexOf('k') >= 0 || s.indexOf('K') >= 0) {
            unit = 1_000;
        }
        s = s.replaceAll("[^0-9.]", "");
        if (s.isBlank() || ".".equals(s)) {
            return "";
        }
        double v;
        try {
            v = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return "";
        }
        long yuan = Math.round(v * unit);
        return yuan <= 0 ? "" : String.valueOf(yuan);
    }

    /** 城市码：已经是 3 位数字码原样返回；否则按城市名查码表；查不到返回 null */
    public static String resolveCityCode(ZhilianOptions options, String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        String trimmed = city.trim();
        if (trimmed.matches("\\d{3}")) {
            return trimmed;
        }
        return options.cityCode(trimmed);
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
