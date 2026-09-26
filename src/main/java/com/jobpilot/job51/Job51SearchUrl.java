package com.jobpilot.job51;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 51job 搜索页 URL 构造。
 * <p>
 * 形如 https://we.51job.com/pc/search?jobArea=070300&salary=05&degree=04%2C05&keyword=%E9%99%88...
 * <p>
 * 四条约束（都来自实测，改之前先读）：
 * <ul>
 *   <li><b>jobArea、salary、degree、workYear 都支持多值</b>，用英文逗号连接。
 *       degree 是"及以上"方向（本科 = 05,06,07），workYear 是"不超过这档"
 *       （1-3年 = 01,02），整串码存在 job51-options.json 里，这里只管拼。
 *       值为 "0"（不限）时整个参数省略，不要传 0——传了会搜出空结果</li>
 *   <li><b>关键词必须 URL 编码</b>。老工程是裸拼中文，浏览器虽然容错，
 *       但日志和重定向都不好看，而且关键词里带空格或 & 时会直接拼坏</li>
 *   <li>URL 不带页码。翻页靠点页码/跳页输入框，见 {@link Job51Driver}</li>
 * </ul>
 */
public final class Job51SearchUrl {

    private static final String BASE = "https://we.51job.com/pc/search";

    private Job51SearchUrl() {
    }

    public static String build(String cityCode, String keyword, Job51Properties.Job51Config config) {
        List<String> params = new ArrayList<>();
        String area = usable(cityCode);
        if (area != null) {
            params.add("jobArea=" + area);
        }
        if (config != null) {
            String salary = usable(config.getSalary());
            if (salary != null) {
                params.add("salary=" + salary);
            }
            // 学历/经验是多值码（"04,05,06,07"）。站点自己发的是 %2C，
            // 这里照发——裸逗号浏览器容错，但服务端网关不一定
            String degree = usable(config.getDegree());
            if (degree != null) {
                params.add("degree=" + degree.replace(",", "%2C"));
            }
            String workYear = usable(config.getExperience());
            if (workYear != null) {
                params.add("workYear=" + workYear.replace(",", "%2C"));
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            params.add("keyword=" + encode(keyword.trim()));
        }
        if (params.isEmpty()) {
            return BASE;
        }
        return BASE + "?" + String.join("&", params);
    }

    /** 城市码：已经是 6 位数字码原样返回；否则按城市名查码表；查不到返回 null */
    public static String resolveCityCode(Job51Options options, String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        String trimmed = city.trim();
        if (trimmed.matches("\\d{6}")) {
            return trimmed;
        }
        return options.cityCode(trimmed);
    }

    /** null / 空 / "0" 都视为"不限"，返回 null 让调用方省略整个参数 */
    private static String usable(String value) {
        if (value == null || value.isBlank() || "0".equals(value.trim())) {
            return null;
        }
        return value.trim();
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
