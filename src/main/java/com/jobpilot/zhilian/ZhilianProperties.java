package com.jobpilot.zhilian;

import com.jobpilot.delivery.PlatformConfig;
import com.jobpilot.system.ConfigService;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

        /** 搜索关键词，逐个跑。出厂给一组服装/零售方向的，用户按自己方向改 */
        private List<String> keywords = new ArrayList<>(List.of("陈列设计", "视觉设计"));

        /** 城市名（如"苏州"）或智联城市码（如 639），启动时统一换成码 */
        private String city = "苏州";
    }
}
