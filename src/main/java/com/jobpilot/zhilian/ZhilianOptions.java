package com.jobpilot.zhilian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.delivery.PlatformOptions;
import org.springframework.stereotype.Component;

/**
 * 智联筛选项码表（城市）。
 * <p>
 * 城市码是智联自己的 3 位内部码（北京=530、苏州=639），与行政编码、
 * Boss 的 9 位码、51job 的 6 位码、猎聘的 3-6 位码都不同，所以一个平台一份 JSON。
 * <p>
 * 薪资没有码表：智联的 sl 参数吃原始金额区间，见
 * {@link ZhilianSearchUrl#salaryRange}。
 */
@Component
public class ZhilianOptions extends PlatformOptions {

    public ZhilianOptions(ObjectMapper objectMapper) {
        super("zhilian-options.json", objectMapper);
    }
}
