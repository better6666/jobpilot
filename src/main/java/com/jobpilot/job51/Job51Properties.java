package com.jobpilot.job51;

import com.jobpilot.delivery.PlatformConfig;
import com.jobpilot.system.ConfigService;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 51job 运行配置。存 config 表（config_key=job51），管理页改完即生效。
 *
 * 筛选项只有 jobArea（城市）和 salary（薪资）两项，都是 51job 自己的码，
 * 码表在 job51-options.json。学历/经验/行业/JD 全交给打分规则。
 */
@Service
public class Job51Properties {

    public static final String CONFIG_KEY = "job51";

    private final ConfigService configService;

    public Job51Properties(ConfigService configService) {
        this.configService = configService;
    }

    public Job51Config get() {
        return configService.getJson(CONFIG_KEY, Job51Config.class, new Job51Config());
    }

    public void save(Job51Config config) {
        configService.setJson(CONFIG_KEY, config);
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Job51Config extends PlatformConfig {

        /** 搜索关键词，逐个跑。出厂给一组服装/零售方向的，用户按自己方向改 */
        private List<String> keywords = new ArrayList<>(List.of("陈列设计", "视觉设计"));

        /** 城市名（如"苏州"）或 51job 地区码（如 070300），启动时统一换成码 */
        private String city = "苏州";
    }
}
