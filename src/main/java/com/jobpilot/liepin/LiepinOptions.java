package com.jobpilot.liepin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.delivery.PlatformOptions;
import org.springframework.stereotype.Component;

/**
 * 猎聘筛选项码表（城市、薪资）。
 *
 * 城市码是猎聘自己的内部码（北京=010、上海=020、苏州=060080），和行政编码、
 * Boss 的 9 位码、51job 的 6 位码都不同，所以一个平台一份 JSON。
 */
@Component
public class LiepinOptions extends PlatformOptions {

    public LiepinOptions(ObjectMapper objectMapper) {
        super("liepin-options.json", objectMapper);
    }
}
