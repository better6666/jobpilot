package com.jobpilot.zhilian;

import com.jobpilot.delivery.RequirementFilter;

/**
 * "我的学历" → 智联搜索地址里的 {@code el}。
 *
 * <p>2026-10-17 真站逐档实测（苏州 / 运营，在页面上选每一档、看请求和返回）：
 * <pre>
 *   初中及以下 9   高中 7   中专/中技 12   大专 5   本科 4   硕士 3   博士 1
 * </pre>
 * 接口字段叫 {@code S_SOU_EDUCATION_LOWESTLEVEL}，但实际是<b>精确匹配、单选</b>：
 * 选大专返回的 20 条全是要求大专的。和猎聘一样，只在"我的学历"是大专及以下时用——
 * 这时高要求岗位占绝大多数；本科及以上的人用了会把学历不限、要求更低的岗位一并筛掉，交给本地过滤。
 */
final class ZhilianEduLevel {

    private ZhilianEduLevel() {
    }

    /** 要拼的 el 码；不该在搜索里筛时返回 null */
    static String code(String myDegree) {
        int rank = RequirementFilter.rankOfDegree(myDegree);
        return switch (rank) {
            case 1 -> "9";
            // 高中和中专同一档，单选只能挑一个：写了中专/中技就按中专，否则按高中
            case 2 -> myDegree != null && (myDegree.contains("中专") || myDegree.contains("中技")) ? "12" : "7";
            case 3 -> "5";
            default -> null;
        };
    }
}
