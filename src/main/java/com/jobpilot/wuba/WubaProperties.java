package com.jobpilot.wuba;

import com.jobpilot.delivery.PlatformConfig;
import com.jobpilot.system.ConfigService;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.stereotype.Service;

/** 58 同城运行配置。存 config 表（config_key=wuba），管理页改完即生效 */
@Service
public class WubaProperties {

    public static final String CONFIG_KEY = "wuba";

    private final ConfigService configService;

    public WubaProperties(ConfigService configService) {
        this.configService = configService;
    }

    public WubaConfig get() {
        return configService.getJson(CONFIG_KEY, WubaConfig.class, new WubaConfig());
    }

    public void save(WubaConfig config) {
        configService.setJson(CONFIG_KEY, config);
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class WubaConfig extends PlatformConfig {
        // 搜索关键词和城市出厂不预填，沿用 PlatformConfig 的空默认
    }
}
