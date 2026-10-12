package com.jobpilot.zhilian;

import com.jobpilot.delivery.PlatformConfig;
import com.jobpilot.system.ConfigService;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 智联招聘运行配置。存 config 表（config_key=zhilian），管理页改完即生效。
 *
 * 和其他三个平台最大的差别：<b>薪资不是档位码，而是原始金额区间</b>
 * （"12000,20000"，单位元）。智联的 sl 参数直接吃这个，没有码表，
 * 所以管理页这里是个文本框而不是下拉框。
 */
@Service
public class ZhilianProperties {

    public static final String CONFIG_KEY = "zhilian";

    private final ConfigService configService;

    public ZhilianProperties(ConfigService configService) {
        this.configService = configService;
    }

    public ZhilianConfig get() {
        return configService.getJson(CONFIG_KEY, ZhilianConfig.class, new ZhilianConfig());
    }

    public void save(ZhilianConfig config) {
        configService.setJson(CONFIG_KEY, config);
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ZhilianConfig extends PlatformConfig {

        // 搜索关键词和城市出厂不预填，沿用 PlatformConfig 的空默认——预填谁的意向就成了谁的私货
    }
}
