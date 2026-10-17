package com.jobpilot.lagou;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** 拉勾筛选项码表。城市参数直接吃中文城市名（{@code city=苏州}），code 存的就是名字 */
@Component
public class LagouOptions extends com.jobpilot.delivery.PlatformOptions {

    public LagouOptions(ObjectMapper objectMapper) {
        super("lagou-options.json", objectMapper);
    }
}
