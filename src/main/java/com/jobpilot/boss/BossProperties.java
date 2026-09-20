package com.jobpilot.boss;

import com.jobpilot.system.ConfigService;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Boss 运行配置。存在 config 表（config_key=boss），管理页改完即生效，不用重启。
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
    public static class BossConfig {

        /** 搜索关键词，逐个跑 */
        private List<String> keywords = new ArrayList<>(List.of("陈列设计"));

        /** 城市名（如"上海"）或城市码（如 101020100），启动时统一换成码 */
        private String city = "上海";

        /** 以下为 URL 筛选项，值为 Boss 选项码，"0"/空 = 不限 */
        private String jobType = "";
        private String salary = "";
        private String experience = "";
        private String degree = "";
        private String scale = "";
        private String industry = "";
        private String stage = "";

        /** 每个关键词最多处理多少个岗位（防一次跑太久触发风控） */
        private int maxJobsPerKeyword = 50;

        /** 两个岗位之间的随机间隔秒数（实际取 [waitSeconds/2, waitSeconds]） */
        private int waitSeconds = 10;

        /** 固定打招呼话术。AI 话术是 P4，届时这里作为兜底 */
        private String sayHi = "您好！我对这个岗位很感兴趣，我的经历与岗位要求比较匹配，方便发一份简历给您看看吗？期待您的回复。";

        /** true = 只走流程不真发消息（预演），记录落库状态为"预演" */
        private boolean dryRun = true;

        /** 扫码登录等待分钟数 */
        private int loginTimeoutMinutes = 5;

        /** HR 活跃描述里带"年"（如"3年前活跃"）视为不活跃，跳过 */
        private boolean filterInactiveHr = false;

        private ScoreRules scoreRules = new ScoreRules();
    }
}
