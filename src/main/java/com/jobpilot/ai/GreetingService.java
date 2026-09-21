package com.jobpilot.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jobpilot.delivery.Delivery;
import com.jobpilot.delivery.DeliveryMapper;
import com.jobpilot.delivery.JobCard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 打招呼话术生成：AI 开着就按岗位 JD 现写一条，不开/失败就用平台配置里的固定话术。
 *
 * <p>四件事都在这里，投递循环只调 {@link #compose}：
 * <ol>
 *   <li><b>提示词</b>：求职者背景（AI 人设）+ 岗位关键字段 + JD 片段 +
 *       随机风格要求。人设没配就直接用固定话术——没有人设生成出来的
 *       是空话，不如不发</li>
 *   <li><b>风格轮换</b>：同一个风格连发十条，HR 一眼就知道是群发。
 *       每次从 {@link #STYLES} 里随机挑一个塞进提示词</li>
 *   <li><b>去重</b>：和最近 30 条已用话术做归一化比对，撞了就让模型
 *       "换个说法"重来一次；还撞就退回固定话术</li>
 *   <li><b>兜底</b>：任何一步不行（没配、接口挂、输出是空的、超时）
 *       都退回固定话术，并带回原因供调用方记到运行日志里</li>
 * </ol>
 *
 * <p>线程：{@link #compose} 在浏览器 dispatcher 线程上被同步调用，
 * 接口超时（最长 45 秒）会拖慢投递节奏，但不会阻塞别的平台——
 * 全局运行锁本来就只允许一个平台在跑。
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

    /** JD 截断长度：提示词太长既慢又贵，关键信息在前 800 字里 */
    private static final int JD_MAX_LEN = 800;
    /** 固定话术兜底时也算去重：和最近这些条比对 */
    private static final int RECENT_GREETINGS = 30;

    private final AiProperties properties;
    private final AiService aiService;
    private final DeliveryMapper deliveryMapper;

    public GreetingService(AiProperties properties, AiService aiService, DeliveryMapper deliveryMapper) {
        this.properties = properties;
        this.aiService = aiService;
        this.deliveryMapper = deliveryMapper;
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
     * @param fallback 平台配置里的固定话术；AI 不可用时用它
     */
    public Greeting compose(JobCard card, String fallback) {
        AiConfig cfg = properties.get();
        if (!cfg.isEnabled()) {
            return Greeting.fallback(fallback, null);
        }
        if (isBlank(cfg.getPersona())) {
            return Greeting.fallback(fallback, "AI 话术开着但没填求职者背景，用固定话术");
        }
        if (isBlank(cfg.getBaseUrl()) || isBlank(cfg.getApiKey()) || isBlank(cfg.getModel())) {
            return Greeting.fallback(fallback, "AI 接口地址/Key/模型没配全，用固定话术");
        }

        List<String> recent = recentGreetings();
        String style = STYLES[ThreadLocalRandom.current().nextInt(STYLES.length)];
        String userPrompt = userPrompt(card, style, null);
        String systemPrompt = systemPrompt(cfg);

        AiService.AiResult result = aiService.chat(cfg.getBaseUrl(), cfg.getApiKey(), cfg.getModel(),
                systemPrompt, userPrompt, cfg.getTemperature());
        String text = result.isOk() ? AiService.cleanGreeting(result.text()) : null;

        // 撞了最近用过的：让模型换个说法再来一次，只重试一次
        if (text != null && isDuplicate(text, recent)) {
            log.debug("话术与最近的重复，换一种说法重试");
            AiService.AiResult retry = aiService.chat(cfg.getBaseUrl(), cfg.getApiKey(), cfg.getModel(),
                    systemPrompt, userPrompt(card, style, recent.get(0)), cfg.getTemperature());
            String retryText = retry.isOk() ? AiService.cleanGreeting(retry.text()) : null;
            if (retryText != null && !isDuplicate(retryText, recent)) {
                return Greeting.ai(retryText);
            }
            return Greeting.fallback(fallback, "AI 话术与最近投递的重复，改用固定话术");
        }
        if (text == null) {
            String reason = result.error() != null ? result.error() : "模型输出为空";
            return Greeting.fallback(fallback, "AI 生成失败（" + reason + "），用固定话术");
        }
        return Greeting.ai(text);
    }

    /** 猎聘 IM 里补的那句追问：一句话的问题，不是招呼 */
    public Greeting composeFollowUp(JobCard card, String fallback) {
        AiConfig cfg = properties.get();
        if (!cfg.isEnabled() || isBlank(cfg.getPersona())
                || isBlank(cfg.getBaseUrl()) || isBlank(cfg.getApiKey()) || isBlank(cfg.getModel())) {
            return Greeting.fallback(fallback, null);
        }
        String userPrompt = "基于下面这个岗位，用中文写一句向 HR 的追问（只输出这一句，"
                + "不超过 40 字，不要解释，不要标题）：\n"
                + "岗位：" + safe(card.getJobName()) + "｜公司：" + safe(card.getBrandName()) + "\n"
                + "我的背景：" + cfg.getPersona().trim();
        AiService.AiResult result = aiService.chat(cfg.getBaseUrl(), cfg.getApiKey(), cfg.getModel(),
                "你是求职助理，帮求职者写一句简短的求职追问。", userPrompt, cfg.getTemperature());
        String text = result.isOk() ? AiService.cleanGreeting(result.text()) : null;
        if (text == null) {
            return Greeting.fallback(fallback, null);
        }
        if (text.length() > 60) {
            text = text.substring(0, 60);
        }
        return Greeting.ai(text);
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
        if (isBlank(cfg.getBaseUrl()) || isBlank(cfg.getApiKey()) || isBlank(cfg.getModel())) {
            return "AI 话术已启用但接口地址/Key/模型没配全，本次将使用固定话术";
        }
        return "AI 话术已启用（" + cfg.getModel() + "），生成失败会自动退回固定话术";
    }

    // ------------------------------------------------------------------
    // 提示词
    // ------------------------------------------------------------------

    private String systemPrompt(AiConfig cfg) {
        return "你是求职者的求职助理，负责写投递时的第一句打招呼语。\n"
                + "硬性要求：\n"
                + "1. 只用中文，1-3 句话，总长不超过 100 字\n"
                + "2. 必须结合下面的【岗位信息】和【我的背景】，提到岗位或公司的具体内容，"
                + "不要放之四海皆可的套话\n"
                + "3. 用\"我\"的口吻直接对 HR 说话，自然、礼貌、不卑不亢\n"
                + "4. 只输出话术本身：不要标题、不要引号、不要解释、不要表情符号堆砌\n"
                + "5. 不要编造背景里没有的经历或数据\n"
                + "6. 不要出现\"您好\"以外的客套三段式（比如\"冒昧打扰...恳请...\"）\n"
                + "\n【我的背景】\n" + cfg.getPersona().trim();
    }

    private String userPrompt(JobCard card, String style, String previous) {
        StringBuilder sb = new StringBuilder();
        sb.append("【岗位信息】\n")
                .append("岗位：").append(safe(card.getJobName())).append('\n')
                .append("公司：").append(safe(card.getBrandName())).append('\n')
                .append("薪资：").append(safe(card.getSalaryDesc())).append('\n')
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
        sb.append("\n【本次要求】").append(style);
        if (previous != null && !previous.isBlank()) {
            sb.append("。注意：上一句已经写过「").append(previous)
                    .append("」，这次换一个完全不同的切入点和句式");
        }
        return sb.toString();
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

    /** 归一化后完全相同才算重复（去掉空白和标点，只比文字） */
    static boolean isDuplicate(String text, List<String> recent) {
        if (text == null || recent == null || recent.isEmpty()) {
            return false;
        }
        String norm = normalize(text);
        if (norm.isEmpty()) {
            return false;
        }
        for (String g : recent) {
            if (norm.equals(normalize(g))) {
                return true;
            }
        }
        return false;
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
