package com.jobpilot.lagou;

import com.jobpilot.delivery.PlatformConfig;
import com.jobpilot.system.ConfigService;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.stereotype.Service;

/** 拉勾运行配置。存 config 表（config_key=lagou），管理页改完即生效 */
@Service
public class LagouProperties {

    public static final String CONFIG_KEY = "lagou";

    private final ConfigService configService;

    public LagouProperties(ConfigService configService) {
        this.configService = configService;
    }

    public LagouConfig get() {
        return configService.getJson(CONFIG_KEY, LagouConfig.class, new LagouConfig());
    }

    public void save(LagouConfig config) {
        configService.setJson(CONFIG_KEY, config);
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class LagouConfig extends PlatformConfig {
        // 搜索关键词和城市出厂不预填，沿用 PlatformConfig 的空默认
    }
}
