package com.jobpilot.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jobpilot.delivery.Delivery;
import com.jobpilot.delivery.DeliveryMapper;
import com.jobpilot.delivery.JobCard;
import com.jobpilot.license.LicenseProperties;
import com.jobpilot.license.LicenseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 打招呼话术生成：AI 开着就按岗位 JD 现写一条，不开/失败就用平台配置里的固定话术。
 *
 * <p>四件事都在这里，投递循环只调 {@link #compose}：
 * <ol>
 *   <li><b>提示词</b>：求职者背景（AI 人设）+ 岗位关键字段 + JD 片段 +
 *       随机风格要求。人设没配就直接用固定话术——没有人设生成出来的
 *       是空话，不如不发</li>
 *   <li><b>多样化</b>：招呼语换语气（{@link #STYLES}），猎聘追问换问什么
 *       （{@link #FOLLOW_UP_ANGLES}）。只换语气换不出差别——问句换个语气还是同一个问句</li>
 *   <li><b>去重</b>：和最近 30 条已用话术比"像不像"（字符二元组重合度 ≥ 0.6 就算撞），
 *       撞了就换个说法重来一次；同时把最近用得滥的开头写进提示词要求换开场。
 *       只比"完全相同"没用，实测同款不同字的两句重合度能到 0.65，
 *       用户看到的就是"话术太同质化"</li>
 *   <li><b>兜底</b>：任何一步不行（没配、接口挂、输出是空的、超时）
 *       都退回固定话术，并带回原因供调用方记到运行日志里</li>
 * </ol>
 *
 * <p>线程：{@link #compose} 在浏览器 dispatcher 线程上被同步调用，
 * 接口超时（最长 45 秒）会拖慢投递节奏，但不会阻塞别的平台——
 * 全局运行锁本来就只允许一个平台在跑。
 *
 * <p>两种模式（{@link AiConfig#getMode()}）：
 * <ul>
 *   <li><b>platform</b>：用平台自备的中转。客户只填人设，接口/key/模型
 *       都不用管；请求由卡密服务端代理，key 不下发到客户端</li>
 *   <li><b>custom</b>：客户自己填的接口，直连</li>
 * </ul>
 * 两条路共用同一套提示词、风格轮换、去重和兜底，只是 {@link #chat} 里
 * 发的目标不同。
 */
@Slf4j
@Service
public class GreetingService {

    /** 风格轮换用。写进提示词，让相邻两条话术的语气有差别 */
    private static final String[] STYLES = {
            "语气简洁直接，两句话说完",
            "语气热情积极，突出主动性",
            "语气稳重专业，强调经验和岗位的匹配点",
            "从作品/项目经历切入，具体一点",
            "先认同公司和业务，再说明自己能补上哪块"
    };

    /**
     * 猎聘追问的角度轮换。<b>只换语气不换</b>：招呼语是一句自我介绍，追问则几乎
     * 必然是个问句，光靠 {@link #STYLES} 换不出差别——实测 483 条话术里 388 条
     * 以"您好"开头，追问清一色"您好，请问该岗位是否…"。所以这里换的是问什么。
     */
    private static final String[] FOLLOW_UP_ANGLES = {
            "问进展：这个岗位还在招吗、大概什么时候安排面试",
            "问要求：HR 最看重哪一块经验或技能",
            "先说自己哪段经历和岗位对得上，再问合不合适",
            "问团队和日常：进来先接手哪部分、团队怎么分工",
            "问落地条件：工作地点、是否接受实习或应届、多久能到岗",
            "提简历/作品集里有对应的东西，问要不要发过来看看"
    };

    /** 追问的长度上限 */
    private static final int FOLLOW_UP_MAX_LEN = 60;
    /** JD 截断长度：提示词太长既慢又贵，关键信息在前 800 字里 */
    private static final int JD_MAX_LEN = 800;
    /** 固定话术兜底时也算去重：和最近这些条比对 */
    private static final int RECENT_GREETINGS = 30;
    /** 算"开头用滥了"时只看最近这几条 */
    private static final int OPENER_WINDOW = 10;
    /** 开头在这几条里出现过这么多次，就要求模型换一个开场 */
    private static final int OPENER_LIMIT = 3;
    /** 开头的长度：去掉标点后取前四个字，"您好请问"和"您好想确"不算同一个开头 */
    private static final int OPENER_LEN = 4;
    /**
     * 相似度阈值（去掉空白标点后的字符二元重合率）。0.6 是拿真实话术量出来的：
     * 「…在苏州的实际工作地点及主要职责是什么」和「…在苏州的实际工作地点主要在
     * 哪里」这种一眼同款的落在 0.65，正常不同角度的两条在 0.5 以下。
     */
    private static final double SIMILAR_MAX = 0.6;

    private final AiProperties properties;
    private final AiService aiService;
    private final DeliveryMapper deliveryMapper;
    private final LicenseService licenseService;
    private final LicenseProperties licenseProperties;
    private final com.jobpilot.license.EntitlementService entitlementService;

    public GreetingService(AiProperties properties, AiService aiService, DeliveryMapper deliveryMapper,
                           LicenseService licenseService, LicenseProperties licenseProperties,
                           com.jobpilot.license.EntitlementService entitlementService) {
        this.properties = properties;
        this.aiService = aiService;
        this.deliveryMapper = deliveryMapper;
        this.licenseService = licenseService;
        this.licenseProperties = licenseProperties;
        this.entitlementService = entitlementService;
    }

    /** 话术结果：text 是要发的内容，note 是"为什么没走 AI"的原因（走了就没有） */
    public record Greeting(String text, String note) {

        static Greeting ai(String text) {
            return new Greeting(text, null);
        }

        static Greeting fallback(String text, String note) {
            return new Greeting(text, note);
        }
    }

    /**
     * 给一个岗位生成话术。
     *
     * @param fallback 平台配置里的固定话术；AI 不可用时用它（会按岗位活用，见 {@link FallbackGreeting}）
     */
    public Greeting compose(JobCard card, String fallback) {
        return compose(card, fallback, null);
    }

    /**
     * @param channel 发到哪个平台（"Boss 直聘"之类），写进提示词让语气对得上渠道；null = 不提
     */
    public Greeting compose(JobCard card, String fallback, String channel) {
        AiConfig cfg = properties.get();
        if (!cfg.isEnabled()) {
            return Greeting.fallback(vary(fallback, card, null), null);
        }
        if (isBlank(cfg.getPersona())) {
            return Greeting.fallback(vary(fallback, card, null), "AI 话术开着但没填求职者背景，用固定话术");
        }
        if (!isConfigured(cfg)) {
            return whenAiFails(fallback, card, null, cfg, configGapReason(cfg));
        }

        List<String> recent = recentGreetings();
        String style = pick(STYLES);
        Variety variety = new Variety(avoidOpeners(recent), OverusedPhrases.find(recent), channel);
        String systemPrompt = systemPrompt(cfg);

        Attempt first = generate(cfg, systemPrompt, userPrompt(card, style, null, variety), recent);
        if (first.problem() == null) {
            return Greeting.ai(first.text());
        }
        if (!first.retryable()) {
            // 接口本身报错/超时：再调一次大概率同样失败，还让这张卡多等一整轮
            return whenAiFails(fallback, card, recent, cfg, "AI 生成失败（" + first.problem() + "）");
        }
        // 只在真的不合格时才重跑一次。用"开头撞了"这种软条件触发重试等于每条都调
        // 两次接口，AI 额度会被这一条规则吃光，所以软条件只写进提示词、不触发重试
        Attempt retry = generate(cfg, systemPrompt,
                userPrompt(card, style, recent.isEmpty() ? null : recent.get(0), variety), recent);
        if (retry.problem() == null) {
            return Greeting.ai(retry.text());
        }
        return whenAiFails(fallback, card, recent, cfg, "AI 生成失败（" + retry.problem() + "）");
    }

    /**
     * AI 开着却拿不到结果时发什么。
     *
     * <p>用户自己写了多条固定话术，说明想自己掌控说法，就轮换着用；否则按岗位和求职者
     * 背景在本地现拼一句（{@link LocalGreetingComposer}），保证每条都不一样。
     * 两条路都走不通才退回那一句固定话术。
     */
    private Greeting whenAiFails(String fallback, JobCard card, List<String> recent, AiConfig cfg,
                                 String reason) {
        List<String> history = recent != null ? recent : recentGreetings();
        if (!isBlank(fallback) && FallbackGreeting.variants(fallback).size() > 1) {
            return Greeting.fallback(vary(fallback, card, history), reason + "，轮换固定话术");
        }
        String local = LocalGreetingComposer.compose(card, cfg.getPersona(), history,
                ThreadLocalRandom.current());
        if (local != null) {
            return Greeting.fallback(local, reason + "，改用按岗位现拼的话术");
        }
        return Greeting.fallback(vary(fallback, card, history), reason + "，用固定话术");
    }

    /** 固定话术按岗位活用。recent 没现成的就现查一次（只在走兜底时才花这次查询） */
    private String vary(String fallback, JobCard card, List<String> recent) {
        if (isBlank(fallback)) {
            return fallback;
        }
        return FallbackGreeting.render(fallback, card, recent != null ? recent : recentGreetings());
    }

    /** 提示词里"别和最近的撞"那几条要求，打包传，免得参数越拉越长 */
    record Variety(String avoidOpeners, List<String> overused, String channel) {
    }

    /** 一次生成的结果：要么有话术，要么有"为什么不能用"的原因 */
    private record Attempt(String text, String problem, boolean retryable) {
    }

    private Attempt generate(AiConfig cfg, String systemPrompt, String userPrompt, List<String> recent) {
        AiService.AiResult result = chat(cfg, systemPrompt, userPrompt);
        if (!result.isOk()) {
            return new Attempt(null, result.error() != null ? result.error() : "模型输出为空",
                    result.error() == null);
        }
        String text = AiService.cleanGreeting(result.text());
        if (text == null) {
            return new Attempt(null, "模型输出为空", true);
        }
        if (isDuplicate(text, recent)) {
            return new Attempt(null, "和最近投递的话术重复", true);
        }
        return new Attempt(text, null, false);
    }

    /** 猎聘 IM 里补的那句追问：一句话的问题，不是招呼 */
    public Greeting composeFollowUp(JobCard card, String fallback) {
        AiConfig cfg = properties.get();
        if (!cfg.isEnabled() || isBlank(cfg.getPersona()) || !isConfigured(cfg)) {
            return Greeting.fallback(vary(fallback, card, null), null);
        }
        List<String> recent = recentGreetings();
        String angle = pick(FOLLOW_UP_ANGLES);
        String system = "你是求职助理，帮求职者写一句简短的求职追问。";
        Variety variety = new Variety(avoidOpeners(recent), OverusedPhrases.find(recent), null);

        Attempt first = generate(cfg, system, followUpPrompt(card, cfg, angle, null, variety), recent);
        String text = first.problem() == null ? first.text() : null;
        if (text == null && first.retryable()) {
            Attempt retry = generate(cfg, system, followUpPrompt(card, cfg, angle,
                    recent.isEmpty() ? null : recent.get(0), variety), recent);
            text = retry.problem() == null ? retry.text() : null;
        }
        if (text == null) {
            return Greeting.fallback(vary(fallback, card, recent), null);
        }
        return Greeting.ai(AiService.clipAtSentence(text, FOLLOW_UP_MAX_LEN));
    }

    private static String pick(String[] options) {
        return options[ThreadLocalRandom.current().nextInt(options.length)];
    }

    /**
     * 按模式选一条路发请求。平台模式由卡密服务端代理，key 不下发；
     * 自定义模式直连客户填的接口。
     */
    private AiService.AiResult chat(AiConfig cfg, String systemPrompt, String userPrompt) {
        AiService.AiResult result;
        if (AiProperties.MODE_CUSTOM.equals(cfg.getMode())) {
            result = aiService.chat(cfg.getBaseUrl(), cfg.getApiKey(), cfg.getModel(),
                    systemPrompt, userPrompt, cfg.getTemperature());
        } else {
            Map<String, String> credentials = licenseService.proxyCredentials();
            if (credentials == null) {
                return AiService.AiResult.fail("卡密未激活，用不了平台话术");
            }
            result = aiService.chatPlatform(licenseProperties.getApiBase(),
                    credentials.get("token"), credentials.get("device_id"),
                    systemPrompt, userPrompt, cfg.getTemperature(), cfg.getModel());
        }
        if (result.isOk()) {
            // 配额按"真实发生的 AI 调用"计：失败重试不计数，用户只为拿到的话术付费
            entitlementService.recordUse("ai_analysis");
        }
        return result;
    }

    /**
     * 该有的都齐了吗。平台模式只要求人设——接口、key、模型都是平台的，
     * 客户不用填，也就不该拿"没配全"去拦他。
     */
    private static boolean isConfigured(AiConfig cfg) {
        if (AiProperties.MODE_CUSTOM.equals(cfg.getMode())) {
            return !isBlank(cfg.getBaseUrl()) && !isBlank(cfg.getApiKey()) && !isBlank(cfg.getModel());
        }
        return true;
    }

    private static String configGapReason(AiConfig cfg) {
        if (AiProperties.MODE_CUSTOM.equals(cfg.getMode())) {
            return "AI 接口地址/Key/模型没配全";
        }
        return "平台没开 AI 中转，请在 AI 话术页改用「我自己的接口」";
    }

    /**
     * AI 这条路现在通不通：开着 + 填了人设 + 接口配全。
     * 只管配置齐不齐，不代表接口一定应答（key 被禁用要到第一次调用才知道）。
     */
    public boolean aiUsable() {
        AiConfig cfg = properties.get();
        return cfg.isEnabled() && !isBlank(cfg.getPersona()) && isConfigured(cfg);
    }

    /**
     * 管理页和运行日志用的一句话状态。没启用返回 null。
     * 半配置状态（开着但没填人设/接口）要说出来，否则用户只会看到
     * "怎么话术没变化"而不知道去哪配。
     */
    public String describe() {
        AiConfig cfg = properties.get();
        if (!cfg.isEnabled()) {
            return null;
        }
        if (isBlank(cfg.getPersona())) {
            return "AI 话术已启用但没填求职者背景，本次将使用固定话术";
        }
        if (!isConfigured(cfg)) {
            return "AI 话术已启用但接口地址/Key/模型没配全，本次将使用固定话术";
        }
        if (AiProperties.MODE_CUSTOM.equals(cfg.getMode())) {
            return "AI 话术已启用（自有接口 " + cfg.getModel() + "），生成失败会自动退回固定话术";
        }
        return "AI 话术已启用（平台提供），生成失败会自动退回固定话术";
    }

    // ------------------------------------------------------------------
    // 提示词
    // ------------------------------------------------------------------

    private String systemPrompt(AiConfig cfg) {
        return "你是求职者的求职助理，负责写投递时的第一句打招呼语。\n"
                + "硬性要求：\n"
                + "1. 只用中文，1-3 句完整的话，总长不超过 90 字，最后一句必须说完\n"
                + "2. 先读懂【岗位信息】这个岗位到底要干什么、看重什么，再从【我的背景】里"
                + "只挑一两点和它最相关的来说。背景是素材库，不是要背的台词："
                + "专业名称、学校、实习经历、软件技能不必每条都提，和这个岗位无关的就别提\n"
                + "3. 必须点到这个岗位或公司的具体内容（做什么业务、要什么能力），不要放之四海皆可的套话\n"
                + "4. 岗位和背景关联不大时，就说自己哪项能力能迁移过去、愿意学什么；"
                + "不要否定自己，不要出现\"不符合\"\"有差距\"\"冒充\"这类话\n"
                + "5. 用\"我\"的口吻直接对 HR 说话，自然、礼貌、不卑不亢\n"
                + "6. 只输出话术本身：不要标题、不要引号、不要解释、不要表情符号堆砌\n"
                + "7. 不要编造背景里没有的经历或数据\n"
                + "8. 不要出现\"您好\"以外的客套三段式（比如\"冒昧打扰...恳请...\"）\n"
                + "\n【我的背景】\n" + cfg.getPersona().trim();
    }

    private String userPrompt(JobCard card, String style, String previous, Variety variety) {
        StringBuilder sb = new StringBuilder();
        sb.append("【岗位信息】\n")
                .append("岗位：").append(safe(card.getJobName())).append('\n')
                .append("公司：").append(safe(card.getBrandName())).append('\n');
        appendIfPresent(sb, "行业", card.getIndustryName());
        appendIfPresent(sb, "公司规模", card.getBrandScaleName());
        sb.append("薪资：").append(safe(card.getSalaryDesc())).append('\n')
                .append("地点：").append(safe(card.getCityName())).append('\n')
                .append("经验要求：").append(safe(card.getJobExperience())).append('\n')
                .append("学历要求：").append(safe(card.getJobDegree())).append('\n');
        String jd = card.getPostDescription();
        if (!isBlank(jd)) {
            String clipped = jd.trim().replaceAll("\\s+", " ");
            if (clipped.length() > JD_MAX_LEN) {
                clipped = clipped.substring(0, JD_MAX_LEN) + "…";
            }
            sb.append("职位描述：").append(clipped).append('\n');
        }
        if (variety != null && !isBlank(variety.channel())) {
            sb.append("\n【发送渠道】").append(channelHint(variety.channel()));
        }
        sb.append("\n【本次要求】").append(style);
        appendVariety(sb, previous, variety);
        return sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String label, String value) {
        if (!isBlank(value)) {
            sb.append(label).append('：').append(value.trim()).append('\n');
        }
    }

    /** 不同平台的消息形态不一样：Boss 是聊天框里的第一句，别的平台是投递附言 */
    static String channelHint(String channel) {
        if (channel.contains("Boss")) {
            return channel + "，这是在聊天窗口里发给 HR 的第一条消息，像真人打字一样口语、简短，一两句就够";
        }
        if (channel.contains("猎聘")) {
            return channel + "，HR 多是猎头或招聘专员，说清自己能做什么，语气稍正式";
        }
        return channel + "，这条会附在简历投递里，说清自己为什么适合即可";
    }

    private String followUpPrompt(JobCard card, AiConfig cfg, String angle, String previous, Variety variety) {
        return "基于下面这个岗位，用中文写一句投递之后追加给 HR 的话。\n"
                + "硬性要求：只输出这一句，30~60 字，必须是完整的一句，不要解释、不要标题、不要引号。"
                + "背景里只挑和岗位相关的一点说，不要把背景整段复述。\n"
                + "【本次角度】" + angle + '\n'
                + "岗位：" + safe(card.getJobName()) + "｜公司：" + safe(card.getBrandName())
                + "｜地点：" + safe(card.getCityName()) + '\n'
                + "我的背景：" + cfg.getPersona().trim() + '\n'
                + appendVariety(new StringBuilder(), previous, variety);
    }

    /** 多样化要求：别连着重复开头、别反复说同一个短语、别用上一句的句式 */
    private static StringBuilder appendVariety(StringBuilder sb, String previous, Variety variety) {
        if (variety != null && !isBlank(variety.avoidOpeners())) {
            sb.append("\n最近已经用过这些开头，请换一个完全不同的开场（可以直接说事）：")
                    .append(variety.avoidOpeners());
        }
        if (variety != null && variety.overused() != null && !variety.overused().isEmpty()) {
            sb.append("\n最近几条话术里反复出现这些说法，这次不要再用（实在要提就换个说法、换个角度）：「")
                    .append(String.join("」「", variety.overused())).append("」");
        }
        if (previous != null && !previous.isBlank()) {
            sb.append("\n上一句已经写过「").append(previous)
                    .append("」，这次换一个完全不同的切入点和句式");
        }
        return sb;
    }

    // ------------------------------------------------------------------
    // 去重
    // ------------------------------------------------------------------

    /** 最近用过的话术（跨平台，撞谁的都算撞） */
    private List<String> recentGreetings() {
        try {
            return deliveryMapper.selectList(new LambdaQueryWrapper<Delivery>()
                            .isNotNull(Delivery::getGreeting)
                            .ne(Delivery::getGreeting, "")
                            .orderByDesc(Delivery::getId)
                            .last("LIMIT " + RECENT_GREETINGS))
                    .stream().map(Delivery::getGreeting).filter(g -> g != null && !g.isBlank()).toList();
        } catch (Exception e) {
            log.debug("读取历史话术失败，跳过去重: {}", e.getMessage());
            return List.of();
        }
    }

    /** 归一化时视为噪声的字符：空白、ASCII 标点、中文标点与引号 */
    private static final String NOISE_CHARS = "\\s\\p{Punct}，。！？、；：“”‘’（）【】《》";

    /**
     * 字面不同但读起来同一句的也算撞——用户报的"话术太同质化"就是这个形态。
     * 归一化后比字符二元组的 Jaccard，重合度到 {@link #SIMILAR_MAX} 以上判重。
     */
    static boolean isDuplicate(String text, List<String> recent) {
        if (text == null || recent == null || recent.isEmpty()) {
            return false;
        }
        Set<String> mine = bigrams(normalize(text));
        if (mine.isEmpty()) {
            return false;
        }
        for (String g : recent) {
            Set<String> other = bigrams(normalize(g));
            if (other.isEmpty()) {
                continue;
            }
            Set<String> smaller = mine.size() <= other.size() ? mine : other;
            Set<String> bigger = mine.size() <= other.size() ? other : mine;
            int shared = 0;
            for (String gram : smaller) {
                if (bigger.contains(gram)) {
                    shared++;
                }
            }
            if ((double) shared / (mine.size() + other.size() - shared) >= SIMILAR_MAX) {
                return true;
            }
        }
        return false;
    }

    /** 提示词里要避开的开头：最近用得最多的那几个，最多报三个 */
    static String avoidOpeners(List<String> recent) {
        if (recent == null || recent.isEmpty()) {
            return "";
        }
        Map<String, Integer> counted = new LinkedHashMap<>();
        for (String g : recent.subList(0, Math.min(OPENER_WINDOW, recent.size()))) {
            String opener = opener(g);
            if (!opener.isEmpty()) {
                counted.merge(opener, 1, Integer::sum);
            }
        }
        List<String> hot = new ArrayList<>();
        counted.forEach((opener, times) -> {
            if (times >= OPENER_LIMIT) {
                hot.add(opener + "…");
            }
        });
        if (hot.isEmpty()) {
            return "";
        }
        return String.join("、", hot.subList(0, Math.min(3, hot.size())));
    }

    private static String opener(String text) {
        String normalized = normalize(text);
        return normalized.length() < OPENER_LEN ? "" : normalized.substring(0, OPENER_LEN);
    }

    private static Set<String> bigrams(String normalized) {
        Set<String> grams = new HashSet<>();
        if (normalized.length() == 1) {
            grams.add(normalized);
        }
        for (int i = 0; i + 1 < normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }

    static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[" + NOISE_CHARS + "]", "");
    }

    private static String safe(String s) {
        return isBlank(s) ? "（未提供）" : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
