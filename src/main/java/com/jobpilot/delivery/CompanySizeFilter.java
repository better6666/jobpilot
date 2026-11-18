package com.jobpilot.delivery;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 在发送前核对招聘网站提供的企业人数区间。 */
public final class CompanySizeFilter {
    private static final String NUMBER = "(\\d+(?:\\.\\d+)?)(万)?";
    private static final Pattern RANGE = Pattern.compile("^" + NUMBER + "[-~至到]" + NUMBER + "人?$");
    private static final Pattern SINGLE = Pattern.compile("^" + NUMBER + "人?(以下|以内|以上|及以上|及以下|\\+)?$");

    private CompanySizeFilter() {
    }

    public static String reject(JobCard card, PlatformConfig config) {
        int minimum = config.getMinCompanySize();
        int maximum = config.getMaxCompanySize();
        if (minimum == 0 && maximum == 0) {
            return null;
        }
        long[] range = parse(card.getBrandScaleName());
        if (range == null) {
            return "企业规模未公开或无法识别，已按人数筛选跳过";
        }
        if (range[0] < minimum || (maximum > 0 && range[1] > maximum)) {
            return "企业规模 " + card.getBrandScaleName() + " 不在所选人数范围内";
        }
        return null;
    }

    static long[] parse(String text) {
        if (text == null) {
            return null;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("[\\s,，]", "").replace('—', '-').replace('–', '-').replace('－', '-');
        Matcher range = RANGE.matcher(normalized);
        if (range.matches()) {
            String lowerUnit = range.group(2);
            if (lowerUnit == null && range.group(4) != null
                    && Double.parseDouble(range.group(1)) < Double.parseDouble(range.group(3))) {
                lowerUnit = range.group(4);
            }
            long lower = number(range.group(1), lowerUnit);
            long upper = number(range.group(3), range.group(4));
            return lower <= upper ? new long[]{lower, upper} : null;
        }
        Matcher single = SINGLE.matcher(normalized);
        if (!single.matches()) {
            return null;
        }
        long value = number(single.group(1), single.group(2));
        String suffix = single.group(3);
        if (suffix == null) {
            return new long[]{value, value};
        }
        return suffix.contains("以下") || suffix.equals("以内")
                ? new long[]{0, value} : new long[]{value, Long.MAX_VALUE};
    }

    private static long number(String digits, String unit) {
        return Math.round(Double.parseDouble(digits) * (unit == null ? 1 : 10000));
    }
}
