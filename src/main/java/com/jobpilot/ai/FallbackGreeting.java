package com.jobpilot.ai;

import com.jobpilot.delivery.JobCard;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 固定话术的"活用"：AI 不可用时也别把同一句话原样发几百遍。
 *
 * <p>实测：AI 的 key 被禁用、额度用完时，一半的投递退回了同一句固定话术，
 * 几百条一字不差。
 * HR 那边看到的就是群发，回复率直接归零。
 *
 * <p>四层处理，从用户写的东西里尽量多挤出变化，但不替用户编内容：
 * <ol>
 *   <li><b>多条轮换</b>：固定话术一行一条，按行随机挑，尽量不和最近用过的撞</li>
 *   <li><b>占位符</b>：{@code {公司}} {@code {岗位}} 换成这张卡片上的值</li>
 *   <li><b>泛指换实名</b>：原话里的「这个岗位」「贵司」换成真实的岗位名、公司名</li>
 *   <li><b>自动点名</b>：前面几条都没用上时，在前面补一句「看到 X 在招 Y」，
 *       让每条至少点名对上号</li>
 * </ol>
 */
final class FallbackGreeting {

    private static final Pattern LEADING_HELLO = Pattern.compile("^(您好|你好)[！!，,。.\\s]*");
    /** 补在前面的点名句。{公司}{岗位} 都有时用 */
    private static final String[] LEADS_FULL = {
            "看到{公司}在招{岗位}，", "注意到{公司}的{岗位}岗位，", "{公司}的{岗位}这个岗位我很关注，"
    };
    /** 只有岗位名时用 */
    private static final String[] LEADS_JOB = {
            "看到贵司在招{岗位}，", "注意到{岗位}这个岗位，"
    };

    private FallbackGreeting() {
    }

    /**
     * @param sayHi  配置里的固定话术，可以多行（一行一条）
     * @param recent 最近发过的话术，用来避开刚用过的那条
     * @return 要发的话；sayHi 为空时原样返回（调用方自己判空拦截）
     */
    static String render(String sayHi, JobCard card, List<String> recent) {
        if (sayHi == null || sayHi.isBlank()) {
            return sayHi;
        }
        String chosen = pickVariant(variants(sayHi), recent);
        String company = card == null ? null : clean(card.getBrandName());
        String job = card == null ? null : clean(card.getJobName());
        boolean hasPlaceholder = chosen.contains("{公司}") || chosen.contains("{岗位}");
        String text = fill(chosen, company, job);
        if (hasPlaceholder || job == null || mentions(text, company, job)) {
            return text;
        }
        // "我对这个岗位很感兴趣"→"我对工具运营这个岗位很感兴趣"：原话里本来就有泛指，
        // 换成实名比在前面再补一句通顺
        String named = nameGenericRefs(text, company, job);
        if (!named.equals(text)) {
            return named;
        }
        String lead = fill(pick(company != null ? LEADS_FULL : LEADS_JOB), company, job);
        Matcher hello = LEADING_HELLO.matcher(text);
        if (hello.find()) {
            // "您好！我对……" → "您好，看到X在招Y，我对……"：问候留在最前面才通顺
            return hello.group(1) + "，" + lead + text.substring(hello.end());
        }
        return lead + text;
    }

    private static final Pattern GENERIC_JOB = Pattern.compile("(这个|该|此|贵)(岗位|职位)");
    private static final Pattern GENERIC_COMPANY = Pattern.compile("贵(公司|司)");

    /** 泛指的"这个岗位/贵司"各只换第一处，换多了读着像机器填表 */
    static String nameGenericRefs(String text, String company, String job) {
        String out = text;
        Matcher j = GENERIC_JOB.matcher(out);
        if (job != null && j.find()) {
            out = out.substring(0, j.start()) + job + "这个" + j.group(2) + out.substring(j.end());
        }
        Matcher c = GENERIC_COMPANY.matcher(out);
        if (company != null && c.find()) {
            out = out.substring(0, c.start()) + company + out.substring(c.end());
        }
        return out;
    }

    static List<String> variants(String sayHi) {
        List<String> out = new ArrayList<>();
        for (String line : sayHi.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out.isEmpty() ? List.of(sayHi.trim()) : out;
    }

    /** 随机挑一条最近没用过的；全用过了就随便挑 */
    private static String pickVariant(List<String> variants, List<String> recent) {
        if (variants.size() == 1) {
            return variants.get(0);
        }
        List<String> fresh = new ArrayList<>();
        for (String v : variants) {
            String core = GreetingService.normalize(fill(v, "", ""));
            boolean used = recent != null && !core.isEmpty()
                    && recent.stream().limit(Math.max(1, variants.size() - 1))
                    .anyMatch(r -> GreetingService.normalize(r).contains(core));
            if (!used) {
                fresh.add(v);
            }
        }
        List<String> pool = fresh.isEmpty() ? variants : fresh;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private static boolean mentions(String text, String company, String job) {
        return (company != null && text.contains(company)) || (job != null && text.contains(job));
    }

    private static String fill(String template, String company, String job) {
        return template
                .replace("{公司}", company == null || company.isEmpty() ? "贵司" : company)
                .replace("{岗位}", job == null || job.isEmpty() ? "这个岗位" : job);
    }

    private static String pick(String[] options) {
        return options[ThreadLocalRandom.current().nextInt(options.length)];
    }

    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
