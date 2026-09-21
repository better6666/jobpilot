package com.jobpilot.job51;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.delivery.PlatformOptions;
import org.springframework.stereotype.Component;

/**
 * 51job 筛选项码表（jobArea 城市、salary 薪资）。
 *
 * 51job 的城市码是 6 位地区码（北京=010000、苏州=070300），与行政编码、
 * Boss 的 9 位码、猎聘的 3-6 位内部码都不同，所以一个平台一份 JSON。
 * 薪资码是 "01"~"12" 两位字符串，带前导零，别当整数处理。
 */
@Component
public class Job51Options extends PlatformOptions {

    public Job51Options(ObjectMapper objectMapper) {
        super("job51-options.json", objectMapper);
    }
}
