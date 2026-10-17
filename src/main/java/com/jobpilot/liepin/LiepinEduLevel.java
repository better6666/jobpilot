package com.jobpilot.liepin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.delivery.RequirementFilter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把"我的学历"变成猎聘搜索地址里的 {@code eduLevel}。
 *
 * <p>为什么要在搜索阶段就筛：只靠本地过滤时，大专用户搜"ai"这类词，猎聘返回的几乎全要
 * 本科以上，扫了一页一个都投不出去，看着就像流程没跑通。2026-10-17 真站实测（苏州 / ai，
 * 第一页 42 个）：不筛时要求大专或不限的只有 8 个，带上 {@code eduLevel=050}（大专）后是 41 个。
 *
 * <p>三条约束（都来自猎聘前端代码 {@code search-jobs} 包）：
 * <ul>
 *   <li>地址参数名是 {@code eduLevel}，搜索页初始化时从地址里读</li>
 *   <li><b>取值不写死</b>：选项列表（{@code educations: [{code, name}]}）来自页面自己发的
 *       {@code pc-search-job-cond-init} 请求，驱动从响应里读，平台改码也不会失效</li>
 *   <li><b>只能单选</b>：选了"大专"就只出要求大专的岗位。所以只在"我的学历"是大专及以下时用——
 *       这时高要求岗位占绝大多数，单选的损失远小于收益；本科及以上的人用了反而会把
 *       学历不限、要求更低的岗位一并筛掉，继续交给本地过滤</li>
 * </ul>
 */
final class LiepinEduLevel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 大专档，见 {@link RequirementFilter#rankOfDegree} */
    private static final int MAX_RANK_TO_FILTER = 3;

    private LiepinEduLevel() {
    }

    /** 这个学历值不值得在猎聘搜索里单选筛 */
    static boolean worthFiltering(String myDegree) {
        int rank = RequirementFilter.rankOfDegree(myDegree);
        return rank >= 1 && rank <= MAX_RANK_TO_FILTER;
    }

    /** 从 cond-init 响应里读学历选项：名字 → 码。结构对不上返回空表 */
    static Map<String, String> parseOptions(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        try {
            JsonNode educations = MAPPER.readTree(body).findValue("educations");
            if (educations == null || !educations.isArray()) {
                return out;
            }
            for (JsonNode e : educations) {
                String code = e.path("code").asText("").trim();
                String name = e.path("name").asText("").trim();
                if (!code.isEmpty() && !name.isEmpty()) {
                    out.put(name, code);
                }
            }
        } catch (Exception ignore) {
            // 响应不是 JSON：当作没拿到选项，退回本地过滤
        }
        return out;
    }

    /** 选项里和"我的学历"同一档的那个码；"不限"不算，找不到返回 null */
    static String pick(Map<String, String> options, String myDegree) {
        int mine = RequirementFilter.rankOfDegree(myDegree);
        if (mine < 1 || options == null) {
            return null;
        }
        for (Map.Entry<String, String> e : options.entrySet()) {
            if (RequirementFilter.rankOfDegree(e.getKey()) == mine) {
                return e.getValue();
            }
        }
        return null;
    }

    static String withEduLevel(String url, String code) {
        if (url == null || code == null || url.contains("eduLevel=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "eduLevel=" + code;
    }
}
