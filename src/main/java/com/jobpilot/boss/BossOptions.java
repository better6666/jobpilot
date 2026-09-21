package com.jobpilot.boss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.delivery.PlatformOptions;
import org.springframework.stereotype.Component;

/**
 * Boss 筛选项码表（城市、薪资、经验、学历、规模、融资阶段、职位类型）。
 *
 * 数据来自 Boss 官网页面自己的下拉选项，打包成 boss-options.json 随 jar 发布——
 * 不依赖任何第三方接口，离线可用，也不会因为 Boss 改接口而失效。
 *
 * 加载逻辑在 {@link PlatformOptions}，四个平台共用；这里只指定资源名。
 */
@Component
public class BossOptions extends PlatformOptions {

    public BossOptions(ObjectMapper objectMapper) {
        super("boss-options.json", objectMapper);
    }
}
