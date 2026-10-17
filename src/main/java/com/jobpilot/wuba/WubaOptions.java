package com.jobpilot.wuba;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 58 同城筛选项码表。
 *
 * <p>城市 code 是<b>二级域名</b>（苏州 = su.58.com），2026-10-17 逐个打开核对过标题；
 * 昆山的 ks 实际是喀什，没收。薪资 code 拼进路径 {@code /pve_5354_3000_4999/}，取自真站筛选栏的链接。
 */
@Component
public class WubaOptions extends com.jobpilot.delivery.PlatformOptions {

    public WubaOptions(ObjectMapper objectMapper) {
        super("wuba-options.json", objectMapper);
    }
}
