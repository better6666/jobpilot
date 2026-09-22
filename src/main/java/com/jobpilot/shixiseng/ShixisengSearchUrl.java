package com.jobpilot.shixiseng;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 实习僧搜索页 URL 构造。
 * <p>
 * 形如 https://www.shixiseng.com/interns?keyword=%E8%A7%86%E8%A7%89&city=%E8%8B%8F%E5%B7%9E&page=2
 * <p>
 * 三条硬约束（都来自实测，改之前先读）：
 * <ul>
 *   <li><b>关键词参数名是 {@code keyword}</b>。{@code kw}/{@code searchWord}/{@code key}
 *       全都被忽略——传了也返回默认列表，看起来"有结果"其实没过滤，最容易踩</li>
 *   <li><b>城市参数吃中文名，不吃数字码</b>，而且<b>必须 URL 编码</b>。
 *       {@code city=苏州}（原始中文）一条都过滤不出来，{@code city=%E8%8B%8F%E5%B7%9E} 才生效。
 *       编码这步放在码表里做（见 {@link ShixisengOptions}），这里直接拼</li>
 *   <li><b>翻页参数是 {@code page}</b>。{@code p}/{@code pageIndex} 都返回第一页</li>
 * </ul>
 * 每页固定 20 条左右，实习僧没有"共 N 条"的可靠 DOM，靠翻到底来判断结束。
 */
public final class ShixisengSearchUrl {

    private static final String BASE = "https://www.shixiseng.com/interns";

    private ShixisengSearchUrl() {
    }

    /**
     * 构造指定页的搜索地址。
     *
     * @param cityCode 已 URL 编码的城市名（来自码表）；null/空 = 全国
     * @param keyword  搜索关键词
     * @param pageNum  页码，从 1 开始；1 时省略（首页不带 page 也能出结果）
     */
    public static String build(String cityCode, String keyword, int pageNum) {
        StringBuilder url = new StringBuilder(BASE).append('?');
        if (keyword != null && !keyword.isBlank()) {
            url.append("keyword=").append(encode(keyword.trim()));
        }
        if (cityCode != null && !cityCode.isBlank()) {
            url.append("&city=").append(cityCode.trim());
        }
        if (pageNum > 1) {
            url.append("&page=").append(pageNum);
        }
        return url.toString();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
