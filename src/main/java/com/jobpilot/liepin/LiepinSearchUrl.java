package com.jobpilot.liepin;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 猎聘搜索页 URL 构造。
 * <p>
 * 形如 https://www.liepin.com/zhaopin/?city=060080&dq=060080&salary=15&currentPage=0&key=%E9%99%88...
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
        if (config != null && config.getSalary() != null && !config.getSalary().isBlank()
                && !"0".equals(config.getSalary().trim())) {
            params.add("salary=" + config.getSalary().trim());
        }
        // 页码从 0 开始。真正翻页靠点"下一页"按钮，这个参数只对首屏有意义
        params.add("currentPage=0");
        if (keyword != null && !keyword.isBlank()) {
            params.add("key=" + encode(keyword.trim()));
        }
        return BASE + "?" + String.join("&", params);
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
