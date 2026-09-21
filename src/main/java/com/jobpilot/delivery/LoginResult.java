package com.jobpilot.delivery;

/** 登录检查结果 */
public enum LoginResult {
    /** profile 里已有登录态，或用户在等待期内扫上了码 */
    LOGGED_IN,
    /** 等满了 timeoutMinutes 还没登录，或被停止 */
    TIMEOUT
}
