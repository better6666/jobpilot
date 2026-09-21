package com.jobpilot.delivery;

/** 单个岗位的投递结果 */
public enum DeliveryStatus {
    /** 真发成功了 */
    DELIVERED,
    /** 预演模式，没真发 */
    PREVIEW,
    /** 单个岗位失败（继续跑下一个） */
    FAILED,
    /** 页面提示今日上限，必须停 */
    LIMIT
}
