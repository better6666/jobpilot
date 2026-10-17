package com.jobpilot.wuba;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 58 同城搜索地址：{@code https://su.58.com/quanzhizhaopin/pve_5354_3000_4999/?key=运营}。
 *
 * <ul>
 *   <li>城市是二级域名（见 {@link WubaOptions}），没配城市时用全国站 www</li>
 *   <li>薪资是路径段 {@code pve_5354_下限_上限}，取自真站筛选栏链接</li>
 *   <li><b>不拼页码</b>：翻页由驱动点页面上的"下一页"链接，和薪资段叠加时的路径顺序没实测过，不去猜</li>
 * </ul>
 */
public final class WubaSearchUrl {

    private WubaSearchUrl() {
    }

    public static String build(String citySub, String keyword, String salaryCode) {
        String sub = citySub == null || citySub.isBlank() ? "www" : citySub.trim();
        StringBuilder url = new StringBuilder("https://").append(sub).append(".58.com/quanzhizhaopin/");
        if (salaryCode != null && !salaryCode.isBlank() && !"0".equals(salaryCode.trim())) {
            url.append("pve_5354_").append(salaryCode.trim()).append('/');
        }
        url.append("?key=").append(URLEncoder.encode(keyword == null ? "" : keyword.trim(), StandardCharsets.UTF_8));
        return url.toString();
    }
}
