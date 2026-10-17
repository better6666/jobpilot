package com.jobpilot.lagou;

import com.jobpilot.delivery.RequirementFilter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 拉勾搜索地址：{@code https://www.lagou.com/wn/zhaopin?kd=运营&city=苏州&pn=2&xl=大专}。
 *
 * <p>2026-10-17 真站实测（苏州 / 运营）：{@code pn} 翻页生效（共 450 个）；{@code xl=大专} 后 15 条全是
 * 要求大专，总数降到 290——<b>精确单选</b>，和猎聘、智联同一个取舍：只在"我的学历"是大专时用。
 * 拉勾的学历档只有不限/大专/本科/硕士/博士，中专高中的人没法在搜索里筛，交给本地过滤。
 */
public final class LagouSearchUrl {

    private LagouSearchUrl() {
    }

    public static String build(String city, String keyword, String myDegree, int pageNum) {
        StringBuilder url = new StringBuilder("https://www.lagou.com/wn/zhaopin?kd=").append(enc(keyword));
        if (city != null && !city.isBlank()) {
            url.append("&city=").append(enc(city));
        }
        if (pageNum > 1) {
            url.append("&pn=").append(pageNum);
        }
        if (RequirementFilter.rankOfDegree(myDegree) == 3) {
            url.append("&xl=").append(enc("大专"));
        }
        return url.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s.trim(), StandardCharsets.UTF_8);
    }
}
