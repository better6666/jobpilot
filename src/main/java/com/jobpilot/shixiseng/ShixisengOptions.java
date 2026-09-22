package com.jobpilot.shixiseng;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 实习僧筛选项码表。
 *
 * 只有一个城市组——实习僧的搜索页就只有城市一个下拉，薪资/经验/学历都没有。
 * 码表里的 code 存的是<b>URL 编码后的城市名</b>（苏州 → %E8%8B%8F%E5%B7%9E）：
 * 实习僧的 city 参数直接吃中文名，不给数字码，所以编码这步放在码表里做，
 * 拼地址时就不用再分心。
 */
@Component
public class ShixisengOptions extends com.jobpilot.delivery.PlatformOptions {

    public ShixisengOptions(ObjectMapper objectMapper) {
        super("shixiseng-options.json", objectMapper);
    }
}
