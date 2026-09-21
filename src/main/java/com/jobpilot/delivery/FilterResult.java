package com.jobpilot.delivery;

/**
 * 一个岗位的过滤判定 + 打分。
 *
 * rejectReason 为 null 表示放行；非 null 时该岗位记"已过滤"，reason 原样落库，
 * 用户复盘规则时看得见是哪条规则拦的。score 不管放行还是拦下都带上——
 * 被拦下的岗位记了分数，用户才知道差多少分。
 */
public record FilterResult(String rejectReason, Integer score) {

    public static FilterResult pass(Integer score) {
        return new FilterResult(null, score);
    }

    public static FilterResult reject(String reason, Integer score) {
        return new FilterResult(reason, score);
    }

    /** 平台没有打分概念时用这个：只判放行/拦下，分数留空 */
    public static FilterResult passNoScore() {
        return new FilterResult(null, null);
    }

    public static FilterResult rejectNoScore(String reason) {
        return new FilterResult(reason, null);
    }
}
