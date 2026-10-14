package com.jobpilot.yingjiesheng;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 应届生求职网筛选项码表。城市 code 存城市名：驱动按名字去点筛选栏，
 * 点不到就本地按名字过滤，两条路都只认名字（城市列表取自 51job 码表，同一家公司的数据）。
 */
@Component
public class YingjieshengOptions extends com.jobpilot.delivery.PlatformOptions {

    public YingjieshengOptions(ObjectMapper objectMapper) {
        super("yingjiesheng-options.json", objectMapper);
    }
}
