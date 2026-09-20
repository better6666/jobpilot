package com.jobpilot.license;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 卡密校验配置，对应 application.yaml 的 license.* 段。
 */
@Data
@ConfigurationProperties(prefix = "license")
public class LicenseProperties {

    /** false = 自用模式，完全跳过卡密校验 */
    private boolean enabled = true;

    /** 卡密服务端地址，例如 https://jobpilot-license.xxx.workers.dev */
    private String apiBase = "";

    /** 预置卡密（也可在激活页填写，填写后以数据库为准） */
    private String cardKey = "";

    /** 心跳间隔（分钟） */
    private int verifyIntervalMinutes = 10;

    /** 服务端不可达时的宽限小时数：期间内继续放行，超时拦截 */
    private int graceHours = 72;

    /** 调试用：true 时即使卡密无效也放行投递 */
    private boolean failOpen = false;
}
