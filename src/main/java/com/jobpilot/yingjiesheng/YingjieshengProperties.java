package com.jobpilot.yingjiesheng;

import com.jobpilot.delivery.PlatformConfig;
import com.jobpilot.system.ConfigService;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.stereotype.Service;

/**
 * 应届生求职网运行配置。存 config 表（config_key=yingjiesheng），管理页改完即生效。
 *
 * <p>城市不进搜索地址：驱动去点筛选栏里的城市名；筛选栏里没有的城市
 * 按全国搜、在 {@link YingjieshengService#filter} 里本地按城市名过滤。
 */
@Service
public class YingjieshengProperties {

    public static final String CONFIG_KEY = "yingjiesheng";

    private final ConfigService configService;

    public YingjieshengProperties(ConfigService configService) {
        this.configService = configService;
    }

    public YingjieshengConfig get() {
        return configService.getJson(CONFIG_KEY, YingjieshengConfig.class, new YingjieshengConfig());
    }

    public void save(YingjieshengConfig config) {
        configService.setJson(CONFIG_KEY, config);
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class YingjieshengConfig extends PlatformConfig {

        // 搜索关键词和城市出厂不预填，沿用 PlatformConfig 的空默认
    }
}
