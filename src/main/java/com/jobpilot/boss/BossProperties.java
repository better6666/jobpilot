package com.jobpilot.boss;

import com.jobpilot.delivery.PlatformConfig;
import com.jobpilot.system.ConfigService;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Boss 运行配置。存在 config 表（config_key=boss），管理页改完即生效，不用重启。
 *
 * 与平台无关的那几项（关键词、城市、间隔、预演开关）在 {@link PlatformConfig} 里，
 * 这里只放 Boss 特有的 URL 筛选项。Boss 是四个平台里筛选维度最多的一个，
 * 别的平台连薪资码表都跟它不是一套。
 *
 * 出厂默认全部中性：不配关键词就投不了（start 会拦），dryRun 默认开，
 * 新用户第一次跑只会"预演"不会真的发消息，看清楚后果再关。
 */
@Service
public class BossProperties {

    public static final String CONFIG_KEY = "boss";

    private final ConfigService configService;

    public BossProperties(ConfigService configService) {
        this.configService = configService;
    }

    public BossConfig get() {
        return configService.getJson(CONFIG_KEY, BossConfig.class, new BossConfig());
    }

    public void save(BossConfig config) {
        configService.setJson(CONFIG_KEY, config);
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class BossConfig extends PlatformConfig {

        /** 搜索关键词，逐个跑 */
        private List<String> keywords = new ArrayList<>(List.of("陈列设计"));

        /** 城市名（如"上海"）或城市码（如 101020100），启动时统一换成码 */
        private String city = "上海";

        /** 固定打招呼话术。AI 话术启用时这里是兜底：生成失败或输出重复，就发这一条 */
        private String sayHi = "您好！我对这个岗位很感兴趣，我的经历与岗位要求比较匹配，方便发一份简历给您看看吗？期待您的回复。";

        /** 以下为 URL 筛选项，值为 Boss 选项码，"0"/空 = 不限 */
        private String jobType = "";
        private String experience = "";
        private String degree = "";
        private String scale = "";
        private String industry = "";
        private String stage = "";

        /** HR 活跃描述里带"年"（如"3年前活跃"）视为不活跃，跳过 */
        private boolean filterInactiveHr = false;
    }
}
