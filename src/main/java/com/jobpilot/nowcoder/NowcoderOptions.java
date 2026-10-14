package com.jobpilot.nowcoder;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 牛客筛选项码表。城市的 code 就是城市名本身：牛客的城市筛不进 URL，
 * 只能拿卡片上的城市文字比（"北京/上海/杭州"这种多城岗位按包含算命中）。
 */
@Component
public class NowcoderOptions extends com.jobpilot.delivery.PlatformOptions {

    public NowcoderOptions(ObjectMapper objectMapper) {
        super("nowcoder-options.json", objectMapper);
    }
}
