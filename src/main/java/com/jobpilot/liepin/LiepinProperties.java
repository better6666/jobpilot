package com.jobpilot.liepin;

import com.jobpilot.delivery.PlatformConfig;
import com.jobpilot.system.ConfigService;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 猎聘运行配置。存 config 表（config_key=liepin），管理页改完即生效。
 *
 * 猎聘的筛选维度比 Boss 少得多：URL 上只有城市和薪资，没有经验/学历/规模。
 * salary 是原样透传的码，猎聘侧没有公开码表——这里配的下拉项来自猎聘页面自己
 * 的薪资筛选项，用户也可以直接填码。
 */
@Service
public class LiepinProperties {

    public static final String CONFIG_KEY = "liepin";

    private final ConfigService configService;

    public LiepinProperties(ConfigService configService) {
        this.configService = configService;
    }

    public LiepinConfig get() {
        return configService.getJson(CONFIG_KEY, LiepinConfig.class, new LiepinConfig());
    }

    public void save(LiepinConfig config) {
        configService.setJson(CONFIG_KEY, config);
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class LiepinConfig extends PlatformConfig {

        /** 搜索关键词，逐个跑。出厂给一组服装/零售方向的，用户按自己方向改 */
        private List<String> keywords = new ArrayList<>(List.of("陈列设计", "视觉设计"));

        /** 城市名（如"苏州"）或猎聘城市码（如 060080），启动时统一换成码 */
        private String city = "苏州";
    }
}
