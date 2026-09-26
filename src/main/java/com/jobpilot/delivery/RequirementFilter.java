package com.jobpilot.delivery;

import java.util.Locale;

/**
 * "我的条件"硬过滤：岗位要求高于求职者本人的学历/经验就拦掉。
 *
 * <p>为什么各平台的 URL 筛选替代不了它：招聘站的学历筛选只有<em>及以上</em>方向
 * （51job 选"大专"发的是 {@code degree=04,05,06,07}），能去掉低门槛岗位，
 * 却挡不住"要求硕士/10年以上"这种够不着的岗位；猎聘、智联、实习僧更是压根
 * 没有学历筛选项。所以这一道必须落在本地，五个平台共用。
 *
 * <p>五家平台对同一个条件的文案写法都不一样（"本科"/"本科及以上"/"统招本科"，
 * "经验不限"/"无需经验"/"在校生/应届生"），所以判定走<em>档位序数</em>而不是
 * 字符串包含：先把字段文案归一到 0~6 的档，再比大小。归一不出来（null）就放行
 * ——宁可多投一个，也不要把采字段的抖动变成"一个都投不出去"。
 */
public final class RequirementFilter {

    private RequirementFilter() {
    }

    /**
     * 判定一个岗位是否够得着。
     *
     * @param card   岗位卡片，只用到 jobDegree / jobExperience 两个字段
     * @param config 平台配置，myDegree / myExperience 为空表示这一项不设限
     * @return null = 放行；否则是拦下的原因，原样落库给用户复盘
     */
    public static String reject(JobCard card, PlatformConfig config) {
        if (card == null || config == null) {
            return null;
        }
        int myDegree = rankOfDegree(config.getMyDegree());
        if (myDegree >= 0) {
            int need = rankOfDegree(card.getJobDegree());
            if (need > myDegree) {
                return "要求" + degreeName(need) + "，高于你的" + degreeName(myDegree);
            }
        }
        int myYears = rankOfYears(config.getMyExperience());
        if (myYears >= 0) {
            int need = rankOfYears(card.getJobExperience());
            if (need > myYears) {
                return "要求" + yearsName(need) + "经验，超过你的" + yearsName(myYears);
            }
        }
        return null;
    }

    /**
     * 学历文案 → 档。返回 -1 表示认不出来（不拦）。
     * 顺序即高低：1 初中及以下 → 6 博士。
     */
    static int rankOfDegree(String text) {
        String t = normalize(text);
        if (t == null) {
            return -1;
        }
        // "不限"必须排在最前判：它是"没有要求"，不是某个档
        if (t.contains("不限") || t.contains("无要求") || t.contains("无学历")) {
            return 0;
        }
        if (t.contains("博士")) {
            return 6;
        }
        // 硕士的写法最多：研究生/硕士/MBA
        if (t.contains("硕士") || t.contains("研究生") || t.contains("mba")) {
            return 5;
        }
        // 本科：统招/全日制/X本（"一本""二本"都算本科档），学士
        if (t.contains("本科") || t.contains("学士") || t.matches(".*[一二三四]本.*")) {
            return 4;
        }
        if (t.contains("大专") || t.contains("专科") || t.contains("高职")) {
            return 3;
        }
        if (t.contains("高中") || t.contains("中专") || t.contains("中技")
                || t.contains("中职") || t.contains("技校")) {
            return 2;
        }
        if (t.contains("初中")) {
            return 1;
        }
        return -1;
    }

    /**
     * 经验文案 → 档。返回 -1 表示认不出来（不拦）。
     * 0 = 无门槛（不限/无需经验/在校生/应届），往上每档约等于一个年限台阶。
     */
    static int rankOfYears(String text) {
        String t = normalize(text);
        if (t == null) {
            return -1;
        }
        if (t.contains("不限") || t.contains("无需") || t.contains("无经验")
                || t.contains("在校") || t.contains("应届") || t.contains("实习")) {
            return 0;
        }
        // 从高往低判：先判"10年以上"，否则"10"会被"1年以下"之类的写法抢走
        if (t.contains("10年")) {
            return 5;
        }
        if (t.contains("5-10") || t.contains("5~10") || t.contains("5到10")) {
            return 4;
        }
        if (t.contains("3-5") || t.contains("3~5") || t.contains("3到5")) {
            return 3;
        }
        if (t.contains("1-3") || t.contains("1~3") || t.contains("1到3")) {
            return 2;
        }
        if (t.contains("1年以下") || t.contains("半年") || t.contains("1年以内")) {
            return 1;
        }
        // "3年""5年"这类单值写法：按"至少要 N 年"落到包含 N 的那一档。
        // 边界刻意宽松（"3年以上"算 1-3 年档）——判严了是少投，判松了只是多投
        int years = leadingNumber(t);
        if (years > 10) {
            return 5;
        }
        if (years > 5) {
            return 4;
        }
        if (years > 3) {
            return 3;
        }
        if (years >= 1) {
            return 2;
        }
        return -1;
    }

    private static int leadingNumber(String text) {
        int n = 0;
        boolean found = false;
        for (char c : text.toCharArray()) {
            if (c >= '0' && c <= '9') {
                n = n * 10 + (c - '0');
                found = true;
            } else if (found) {
                break;
            }
        }
        return found ? n : -1;
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.toLowerCase(Locale.ROOT).replace(" ", "").replace("　", "");
    }

    /** 拦下的原因里要写人话，档位反过来映射成最常见的叫法 */
    private static String degreeName(int rank) {
        return switch (rank) {
            case 1 -> "初中及以下";
            case 2 -> "高中/中专";
            case 3 -> "大专";
            case 4 -> "本科";
            case 5 -> "硕士";
            case 6 -> "博士";
            default -> "学历";
        };
    }

    private static String yearsName(int rank) {
        return switch (rank) {
            case 0 -> "无经验";
            case 1 -> "1年以下";
            case 2 -> "1-3年";
            case 3 -> "3-5年";
            case 4 -> "5-10年";
            case 5 -> "10年以上";
            default -> "若干年";
        };
    }
}
