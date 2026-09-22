package com.jobpilot.license;

import java.util.Map;

/**
 *  entitlement 快照：当前设备能用什么、今天还能用多少。
 *
 * <p>由 {@link EntitlementService} 从卡密服务端拉取并缓存。服务端是唯一真相源，
 * 这里的副本只用于「服务端暂时连不上时别把用户锁死」。
 *
 * <p>功能键和配额键是前后端共用的契约，改名要三边一起改
 * （plans.ts / 这里 / 前端页面）。
 */
public class Entitlement {

    /** trial=体验版 | standard=标准版 | advanced=进阶版 */
    private String plan = "trial";
    private String planName = "体验版";
    /** 卡片当前是否可用（未到期、未作废、次数未用完） */
    private boolean usable;
    private String expiresAt;
    private long remainingDays;
    private Long quotaTotal;
    private Long quotaRemaining;
    private boolean recommended;

    private Map<String, Boolean> features = Map.of();
    private Map<String, Integer> quotas = Map.of();

    public String getPlan() {
        return plan;
    }

    public void setPlan(String plan) {
        this.plan = plan;
    }

    public String getPlanName() {
        return planName;
    }

    public void setPlanName(String planName) {
        this.planName = planName;
    }

    public boolean isUsable() {
        return usable;
    }

    public void setUsable(boolean usable) {
        this.usable = usable;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }

    public long getRemainingDays() {
        return remainingDays;
    }

    public void setRemainingDays(long remainingDays) {
        this.remainingDays = remainingDays;
    }

    public Long getQuotaTotal() {
        return quotaTotal;
    }

    public void setQuotaTotal(Long quotaTotal) {
        this.quotaTotal = quotaTotal;
    }

    public Long getQuotaRemaining() {
        return quotaRemaining;
    }

    public void setQuotaRemaining(Long quotaRemaining) {
        this.quotaRemaining = quotaRemaining;
    }

    public boolean isRecommended() {
        return recommended;
    }

    public void setRecommended(boolean recommended) {
        this.recommended = recommended;
    }

    public Map<String, Boolean> getFeatures() {
        return features;
    }

    public void setFeatures(Map<String, Boolean> features) {
        this.features = features == null ? Map.of() : features;
    }

    public Map<String, Integer> getQuotas() {
        return quotas;
    }

    public void setQuotas(Map<String, Integer> quotas) {
        this.quotas = quotas == null ? Map.of() : quotas;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 功能是否开放。契约里没写的功能一律按关闭处理——新功能默认不送 */
    public boolean has(String feature) {
        return Boolean.TRUE.equals(features.get(feature));
    }

    /** 配额上限；契约里没写按 0 处理 */
    public int quota(String key) {
        Integer v = quotas.get(key);
        return v == null ? 0 : v;
    }
}
