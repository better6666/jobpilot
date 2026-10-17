package com.jobpilot.job51;

import com.jobpilot.delivery.RequirementFilter;

import java.util.Map;
import java.util.Set;

/**
 * 51job 搜索地址里学历（degree）、经验（workYear）参数的取值。
 *
 * <p>2026-10-17 真站逐个码实测（苏州 / 运营，每个码看返回岗位的要求）：
 * <pre>
 *   degree   01 初中及以下  02 高中/中专  03 大专  04 本科  05 硕士  06 博士  07 学历不限
 *   workYear 01 在校生/应届生  06 无需经验  其余几档按区间重叠筛，交叉很多
 * </pre>
 * 以前的码表整体错了一位（把 04 当大专、07 当博士），而且是"X 及以上"的方向——
 * 选"大专及以上"实际搜出来的是本科到博士，正好和求职者想要的反过来。
 * 多个码用逗号同时传，实测 {@code 01,02,03,07} 返回的全是要求不高于大专的岗位。
 */
final class Job51Codes {

    private static final String UNLIMITED = "07";
    /** 学历档位（见 {@link RequirementFilter#rankOfDegree}）→ 51job 码 */
    private static final Map<Integer, String> DEGREE_BY_RANK = Map.of(
            1, "01", 2, "02", 3, "03", 4, "04", 5, "05", 6, "06");

    /** 旧码表的学历值（"及以上"方向，且错位一档）：存着这些的老配置一律当没选 */
    private static final Set<String> LEGACY_DEGREE = Set.of(
            "02,03,04,05,06,07", "03,04,05,06,07", "04,05,06,07", "05,06,07", "06,07", "07");
    /** 旧码表的经验值 → 补上"无需经验"(06) 的新值 */
    private static final Map<String, String> LEGACY_EXPERIENCE = Map.of(
            "01", "01,06", "01,02", "01,06,07,02", "01,02,03", "01,06,07,02,03",
            "01,02,03,04", "01,06,07,02,03,04");

    private Job51Codes() {
    }

    /**
     * 实际要传的学历码。下拉框选了（新码表）就用它；没选或是旧码，就按"我的学历"算：
     * 不高于我的各档 + 学历不限。都没有返回 null（不筛）
     */
    static String degree(String selected, String myDegree) {
        String s = selected == null ? "" : selected.trim();
        if (!s.isEmpty() && !"0".equals(s) && !LEGACY_DEGREE.contains(s)) {
            return s;
        }
        int mine = RequirementFilter.rankOfDegree(myDegree);
        if (mine < 1) {
            return null;
        }
        StringBuilder codes = new StringBuilder(UNLIMITED);
        for (int rank = 1; rank <= Math.min(mine, 6); rank++) {
            codes.append(',').append(DEGREE_BY_RANK.get(rank));
        }
        return codes.toString();
    }

    /** 经验码。旧值换成补了"无需经验"的新值；"0"/空 = 不筛 */
    static String experience(String selected) {
        String s = selected == null ? "" : selected.trim();
        if (s.isEmpty() || "0".equals(s)) {
            return null;
        }
        return LEGACY_EXPERIENCE.getOrDefault(s, s);
    }
}
