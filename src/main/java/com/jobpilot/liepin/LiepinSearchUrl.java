package com.jobpilot.liepin;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 猎聘搜索页 URL 构造。
 * <p>
 * 形如 https://www.liepin.com/zhaopin/?city=060080&dq=060080&salaryCode=5%2410&currentPage=0&key=%E6%96%B0...
 * <p>
 * 两条硬约束（都是实测出来的，改之前先读）：
 * <ul>
 *   <li><b>city 和 dq 必须同值双写</b>：只传 city 时大区筛选不生效，页面看着像
 *       搜了其实没筛，用户配了城市却投到全国去</li>
 *   <li>城市码留空时 city/dq 两个参数整个省略（全国），不要传空串</li>
 * </ul>
 * 关键词这里做 URL 编码：老工程是裸拼中文、靠浏览器容错，编码后请求等价但更稳，
 * 日志里也看得懂。
 */
public final class LiepinSearchUrl {

    private static final String BASE = "https://www.liepin.com/zhaopin/";

    private LiepinSearchUrl() {
    }

    public static String build(String cityCode, String keyword, LiepinProperties.LiepinConfig config) {
        List<String> params = new ArrayList<>();
        boolean hasCity = cityCode != null && !cityCode.isBlank();
        if (hasCity) {
            params.add("city=" + cityCode.trim());
            params.add("dq=" + cityCode.trim());
        }
        String salary = config == null ? null : salaryCode(config.getSalary());
        if (salary != null) {
            // 参数名是 salaryCode，取值形如 5$10（猎聘前端代码 + 真站实测）。
            // 以前发的是 salary=15，猎聘根本不认，薪资筛选从来没生效过
            params.add("salaryCode=" + salary.replace("$", "%24"));
        }
        // 页码从 0 开始。真正翻页靠点"下一页"按钮，这个参数只对首屏有意义
        params.add("currentPage=0");
        if (keyword != null && !keyword.isBlank()) {
            params.add("key=" + encode(keyword.trim()));
        }
        return BASE + "?" + String.join("&", params);
    }

    /** 旧码表的薪资值（猎聘不认的那套）→ 最接近的官方区间 */
    private static final java.util.Map<String, String> LEGACY_SALARY = java.util.Map.of(
            "10", "3$5", "15", "5$10", "20", "10$20", "25", "20$40", "30", "20$40", "35", "40$60");

    /** 实际要发的薪资码；空/"0"/不认识的旧值返回 null（不筛） */
    static String salaryCode(String selected) {
        if (selected == null || selected.isBlank() || "0".equals(selected.trim())) {
            return null;
        }
        String s = selected.trim();
        if (s.contains("$")) {
            return s;
        }
        return LEGACY_SALARY.get(s);
    }

    /** 城市码：已经是 3-6 位数字码原样返回；否则按城市名查码表；查不到返回 null */
    public static String resolveCityCode(LiepinOptions options, String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        String trimmed = city.trim();
        if (trimmed.matches("\\d{2,6}")) {
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
