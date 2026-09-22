package com.jobpilot.shixiseng;

import com.jobpilot.delivery.PlatformConfig;
import com.jobpilot.system.ConfigService;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 实习僧运行配置。存 config 表（config_key=shixiseng），管理页改完即生效。
 *
 * <p>和另四个平台最大的差别：<b>实习僧只有实习/校招岗</b>，没有全职筛选那一套，
 * 所以筛选项只有城市；薪资、经验、学历在列表页根本采不到（列表只有
 * 「日薪 / 每周几天 / 几个月」三组），要筛只能等详情页的 JD 拉回来再打分。
 */
@Service
public class ShixisengProperties {

    public static final String CONFIG_KEY = "shixiseng";

    private final ConfigService configService;

    public ShixisengProperties(ConfigService configService) {
        this.configService = configService;
    }

    public ShixisengConfig get() {
        return configService.getJson(CONFIG_KEY, ShixisengConfig.class, new ShixisengConfig());
    }

    public void save(ShixisengConfig config) {
        configService.setJson(CONFIG_KEY, config);
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ShixisengConfig extends PlatformConfig {

        /** 搜索关键词，逐个跑。出厂给服装/零售方向的，用户按自己方向改 */
        private List<String> keywords = new ArrayList<>(List.of("陈列", "视觉设计", "平面设计"));

        /** 城市名，码表里查不到就按名字原样 URL 编码后拼进地址 */
        private String city = "苏州";
    }
}
