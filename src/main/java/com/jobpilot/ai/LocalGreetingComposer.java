package com.jobpilot.ai;

import com.jobpilot.delivery.JobCard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * AI 调不通时，在本地按岗位现拼一句招呼语。
 *
 * <p>为什么要有它：实测国内直连卡密服务端时 SSL 握手失败、超时、中转 502 是一阵一阵的，
 * 重试三次也会连着失败，大部分投递都退回了同一句固定话术（只换了岗位名）。固定话术句式只有一个，再怎么替换岗位名 HR 看着还是群发。
 *
 * <p>拼法：开场 × 岗位切入 × 背景要点 × 结尾，四段各有一组说法。背景要点从
 * 「求职者背景」里按逗号拆出来，挑和这个岗位字面最相关的一两条，改成"我……"的口吻。
 * 拼出来的和最近发过的逐条比像不像（{@link GreetingService#isDuplicate}），撞了就换一组再拼。
 * 几段的组合有几千种，不会两条一样。
 */
final class LocalGreetingComposer {

    private static final String[] OPENERS = {"您好，", "HR您好，", "你好，", "", "您好呀，", "打扰一下，"};

    /** 岗位切入。{公司}{岗位}{行业} 由卡片填；缺公司名的模板会被跳过 */
    private static final String[] HOOKS_WITH_COMPANY = {
            "看到{公司}在招{岗位}", "刚看了{公司}的{岗位}岗位", "注意到{公司}这边在招{岗位}",
            "{公司}的{岗位}我挺感兴趣的", "想应聘一下{公司}的{岗位}"
    };
    private static final String[] HOOKS_JOB_ONLY = {
            "看到贵司在招{岗位}", "{岗位}这个岗位我挺感兴趣的", "对{岗位}这个岗位很感兴趣",
            "想了解下{岗位}这个岗位"
    };
    private static final String[] HOOKS_WITH_INDUSTRY = {
            "看到{公司}在做{行业}，{岗位}这块我想试试", "{行业}方向一直想进，看到{公司}在招{岗位}"
    };

    /** 两条背景要点之间的连接（第二条去掉开头的"我"再接） */
    private static final String[] JOINERS = {"，也", "，另外", "，还"};
    /** 第二条是"我是X专业的"时用：「，同时是X专业的」读着别扭 */
    private static final String[] MAJOR_JOINERS = {"，学的是", "，本身是"};

    private static final String[] CLOSINGS = {
            "方便的话想发份简历给您看看。", "请问这个岗位还在招吗？", "想进一步了解下岗位要求。",
            "有机会的话想和您详细聊聊。", "简历和作品可以随时发您。", "不知道合不合适，想听听您的意见。",
            "期待您的回复。", "可以的话想约个时间聊聊。"
    };

    private static final int MAX_LEN = 90;
    private static final int MAX_TRIES = 16;

    private LocalGreetingComposer() {
    }

    /**
     * @return 拼好的一句；背景是空的、或怎么拼都和最近的撞，返回 null 交给调用方用固定话术
     */
    static String compose(JobCard card, String persona, List<String> recent, Random rnd) {
        List<String> facts = facts(persona, card);
        if (facts.isEmpty()) {
            return null;
        }
        String company = clean(card == null ? null : card.getBrandName());
        String job = clean(card == null ? null : card.getJobName());
        String industry = clean(card == null ? null : card.getIndustryName());
        Set<String> hotOpeners = hotOpeners(recent);
        String fallbackCandidate = null;
        for (int i = 0; i < MAX_TRIES; i++) {
            String text = once(facts, company, job, industry, card, recent, rnd);
            if (text.length() > MAX_LEN) {
                continue;
            }
            if (GreetingService.isDuplicate(text, recent)) {
                continue;
            }
            if (fallbackCandidate == null) {
                fallbackCandidate = text;
            }
            // 开头连着几条一样也像群发：尽量换个开头，实在换不开就用第一条不撞的
            if (!hotOpeners.contains(opener(text))) {
                return text;
            }
        }
        return fallbackCandidate;
    }

    private static String once(List<String> facts, String company, String job, String industry,
                               JobCard card, List<String> recent, Random rnd) {
        StringBuilder sb = new StringBuilder();
        sb.append(OPENERS[rnd.nextInt(OPENERS.length)]);
        if (job != null) {
            sb.append(hook(company, job, industry, rnd)).append(rnd.nextBoolean() ? "。" : "，");
        }
        List<String> ranked = rankFacts(facts, card, recent, rnd);
        sb.append(ranked.get(0));
        // 三分之二的时候再带第二条，句子长短也跟着变
        if (ranked.size() > 1 && rnd.nextInt(3) > 0) {
            sb.append(joinSecond(ranked.get(1), rnd));
        }
        String hookText = sb.toString();
        sb.append("。").append(closing(hookText, recent, rnd));
        return sb.toString().replace("，。", "。").replace("。。", "。");
    }

    /** 第二条要点怎么接：去掉开头的"我"（"也我会用"不通），专业那条换个说法 */
    static String joinSecond(String second, Random rnd) {
        if (second.startsWith("我是") && second.endsWith("的")) {
            String major = second.substring(2, second.length() - 1);
            String joiner = MAJOR_JOINERS[rnd.nextInt(MAJOR_JOINERS.length)];
            return joiner.equals("，学的是") ? joiner + major : joiner + major + "的";
        }
        String body = second.startsWith("我") ? second.substring(1) : second;
        return JOINERS[rnd.nextInt(JOINERS.length)] + body;
    }

    /** 结尾别和前面撞意思：前面说了"想了解下这个岗位"，结尾就别再"想进一步了解下" */
    private static String closing(String before, List<String> recent, Random rnd) {
        List<String> lastTwo = recent == null ? List.of() : recent.subList(0, Math.min(2, recent.size()));
        List<String> pool = new ArrayList<>();
        for (String c : CLOSINGS) {
            boolean echoes = (before.contains("了解") && c.contains("了解"))
                    || (before.contains("感兴趣") && c.contains("感兴趣"));
            // 结尾也轮着用：连着两条都是"期待您的回复"一样像群发
            boolean justUsed = lastTwo.stream().anyMatch(r -> r.endsWith(c));
            if (!echoes && !justUsed) {
                pool.add(c);
            }
        }
        return pool.isEmpty() ? CLOSINGS[rnd.nextInt(CLOSINGS.length)] : pool.get(rnd.nextInt(pool.size()));
    }

    private static String hook(String company, String job, String industry, Random rnd) {
        List<String> pool = new ArrayList<>(List.of(HOOKS_JOB_ONLY));
        if (company != null) {
            pool.addAll(List.of(HOOKS_WITH_COMPANY));
            if (industry != null) {
                pool.addAll(List.of(HOOKS_WITH_INDUSTRY));
            }
        }
        String t = pool.get(rnd.nextInt(pool.size()));
        return t.replace("{公司}", company == null ? "贵司" : company)
                .replace("{岗位}", job)
                .replace("{行业}", industry == null ? "" : industry);
    }

    /**
     * 把背景拆成一条条"我……"的短句。意向城市、期望薪资这类不是经历的不要：
     * 投 A 城的岗位还说"意向 B 城"，比不说更糟
     */
    static List<String> facts(String persona, JobCard card) {
        List<String> out = new ArrayList<>();
        if (persona == null || persona.isBlank()) {
            return out;
        }
        for (String raw : persona.split("[，,。；;、\\n]+")) {
            String c = raw.trim().replaceAll("等$", "");
            if (c.length() < 2 || c.length() > 30) {
                continue;
            }
            if (c.matches("^(意向|期望|希望|目标城市|求职意向|期待).*") || c.matches(".*(薪资|待遇|到岗).*")) {
                continue;
            }
            out.add(firstPerson(c));
        }
        return out;
    }

    static String firstPerson(String clause) {
        if (clause.startsWith("我")) {
            return clause;
        }
        if (clause.endsWith("专业") || clause.endsWith("专业毕业")) {
            return "我是" + clause.replaceAll("毕业$", "") + "的";
        }
        if (clause.matches("^(做过|参与过|参加过|负责过|有|会|熟悉|擅长|精通|懂|能|学过|在|曾|拿过|获得).*")) {
            return "我" + clause;
        }
        if (clause.endsWith("毕业") || clause.endsWith("届")) {
            return "我是" + clause + "的";
        }
        return "我的情况是" + clause;
    }

    /**
     * 排背景要点：和岗位名 + JD 字面越像越靠前，最近几条里刚说过的往后排。
     * 只按相关度排的话，岗位名和专业名撞上一个字（比如都带"设计"），专业那条就永远打头——
     * 实测 12 条里出现 10 次，换了开头结尾照样像同一句
     */
    private static List<String> rankFacts(List<String> facts, JobCard card, List<String> recent, Random rnd) {
        String target = "";
        if (card != null) {
            target = (card.getJobName() == null ? "" : card.getJobName())
                    + (card.getPostDescription() == null ? ""
                    : card.getPostDescription().substring(0, Math.min(400, card.getPostDescription().length())));
        }
        Set<String> targetGrams = grams(GreetingService.normalize(target));
        java.util.Map<String, Integer> score = new java.util.HashMap<>();
        for (String f : facts) {
            score.put(f, Math.min(overlap(grams(GreetingService.normalize(f)), targetGrams), 3) * 2
                    - recentUses(f, recent) * 3);
        }
        List<String> shuffled = new ArrayList<>(facts);
        java.util.Collections.shuffle(shuffled, rnd);
        shuffled.sort((a, b) -> score.get(b) - score.get(a));
        return shuffled;
    }

    /** 这条要点在最近 4 条里被说过几次（比核心词：去掉"我""我是""的"） */
    private static int recentUses(String fact, List<String> recent) {
        if (recent == null || recent.isEmpty()) {
            return 0;
        }
        String core = GreetingService.normalize(fact.replaceFirst("^我(是|的情况是)?", "").replaceAll("的$", ""));
        if (core.length() < 3) {
            return 0;
        }
        String probe = core.substring(0, Math.min(6, core.length()));
        int n = 0;
        for (String r : recent.subList(0, Math.min(4, recent.size()))) {
            if (GreetingService.normalize(r).contains(probe)) {
                n++;
            }
        }
        return n;
    }

    private static Set<String> grams(String s) {
        Set<String> g = new HashSet<>();
        for (int i = 0; i + 2 <= s.length(); i++) {
            g.add(s.substring(i, i + 2));
        }
        return g;
    }

    private static int overlap(Set<String> a, Set<String> b) {
        int n = 0;
        for (String x : a) {
            if (b.contains(x)) {
                n++;
            }
        }
        return n;
    }

    private static Set<String> hotOpeners(List<String> recent) {
        Set<String> hot = new HashSet<>();
        if (recent == null) {
            return hot;
        }
        for (String r : recent.subList(0, Math.min(3, recent.size()))) {
            hot.add(opener(r));
        }
        return hot;
    }

    /** 去掉标点后的前四个字 */
    private static String opener(String text) {
        String n = GreetingService.normalize(text);
        return n.length() < 4 ? n : n.substring(0, 4);
    }

    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
