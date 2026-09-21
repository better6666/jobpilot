package com.jobpilot.license;

import lombok.Data;

import java.time.Instant;

/**
 * 落库的卡密运行时状态（config 表 license 键）。
 */
@Data
public class LicenseRecord {

    private String cardKey;

    private String token;

    private String deviceId;

    /** 最近一次服务端确认有效的时间，宽限判断的基准 */
    private Instant lastVerifyOkAt;

    private String type;

    private String expiresAt;

    private Long remainingDays;

    private Long quotaTotal;

    private Long quotaRemaining;

    /** 次数卡已用次数，来自 /report 的响应；时长卡恒为 null */
    private Long quotaUsed;
}
