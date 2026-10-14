package com.jobpilot.nowcoder;

import com.jobpilot.delivery.PlatformConfig;
import com.jobpilot.system.ConfigService;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.stereotype.Service;

/**
 * 牛客运行配置。存 config 表（config_key=nowcoder），管理页改完即生效。
 *
 * <p>牛客的搜索页<b>不吃 URL 里的城市和页码参数</b>（2026-10-14 实测：
 * {@code &city=苏州&page=2} 原样返回第一页全国结果），城市和招聘类型
 * 只能在本地按卡片字段筛，见 {@link NowcoderService#filter}。
 */
@Service
public class NowcoderProperties {

    public static final String CONFIG_KEY = "nowcoder";

    private final ConfigService configService;

    public NowcoderProperties(ConfigService configService) {
        this.configService = configService;
    }

    public NowcoderConfig get() {
        return configService.getJson(CONFIG_KEY, NowcoderConfig.class, new NowcoderConfig());
    }

    public void save(NowcoderConfig config) {
        configService.setJson(CONFIG_KEY, config);
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class NowcoderConfig extends PlatformConfig {

        /** 校招 / 实习 / 社招，空 = 不限。和岗位名前缀「校招 | 」比 */
        private String recruitType = "";
    }
}
