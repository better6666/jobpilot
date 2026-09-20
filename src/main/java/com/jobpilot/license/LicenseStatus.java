package com.jobpilot.license;

import lombok.Data;

import java.time.Instant;

/**
 * 卡密状态快照，返回给前端展示。
 */
@Data
public class LicenseStatus {

    private boolean enabled;

    private LicenseState state;

    /** 是否放行投递 */
    private boolean allowed;

    private String message;

    /** 卡密类型：time / quota / trial */
    private String type;

    private String cardKeyMasked;

    private String expiresAt;

    private Long remainingDays;

    private Long quotaTotal;

    private Long quotaRemaining;

    private Instant lastVerifyAt;
}
