package com.jobpilot.ai;

import lombok.Data;

/**
 * AI 话术配置。存 config 表（config_key=ai），管理页改完即生效，不用重启。
 *
 * <p>接的是 <b>OpenAI 兼容接口</b>：官方（api.openai.com、api.deepseek.com）和
 * 各种中转站都一样，差别只在 {@link #baseUrl} 和 {@link #model}。地址带不带
 * {@code /v1}、是不是把完整端点粘进来，都由 {@link AiService#normalizeBaseUrl}
 * 归一，用户不用猜格式。
 *
 * <p>{@link #apiKey} 是敏感字段：GET 一律打码返回，PUT 时 null/不传 = 保持原值、
 * 空串 = 清除、其他 = 换成新值（见 {@link AiProperties#save}）。管理页是局域网
 * 也能访问的本地服务，不明文回显。
 */
@Data
public class AiConfig {

    /** 总开关。关了就永远用各平台配置里的固定话术 */
    private boolean enabled = false;

    /**
     * 话术从哪来：{@code platform} = 用平台自备的中转（客户什么都不用填），
     * {@code custom} = 用客户自己填的接口。
     *
     * <p>默认 null 而不是 "platform"：null 表示"这份配置是加 mode 字段之前存的"，
     * 由 {@link AiProperties#get()} 按有没有填过自己的 key 决定迁移到哪个模式。
     * 填过 key 的老用户保持 custom，没填过的落到 platform——升级不会
     * 悄悄把别人配好的接口换成平台的。
     */
    private String mode;

    /** 接口地址，如 https://api.openai.com/v1 或某个中转站地址 */
    private String baseUrl = "";

    /**
     * API Key。只在保存时写入，读取时打码。
     *
     * <p>默认 null 而不是空串：管理页保存时如果请求体里没带这个字段
     * （用户没动输入框），Jackson 会保留字段初始值，只有 null 才表示
     * "没提过"，才能按 {@link AiProperties#save} 的规则保留原值。
     * 空串是用户显式清除。
     */
    private String apiKey;

    /** 模型名，如 gpt-4o-mini / deepseek-chat；可从中转站的 /models 拉列表 */
    private String model = "";

    /** 求职者背景介绍（AI 人设）：生成话术的核心素材，没配就不生成 */
    private String persona = "";

    /** 采样温度 0~2，越大越跳脱。默认 0.9 */
    private double temperature = 0.9;
}
