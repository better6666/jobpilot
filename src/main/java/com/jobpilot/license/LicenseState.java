package com.jobpilot.license;

/**
 * 卡密状态机。
 *
 * 放行投递的只有 ACTIVE / GRACE / OFF（以及 failOpen 调试开关）。
 */
public enum LicenseState {
    /** 未启用校验（自用模式） */
    OFF,
    /** 未填写卡密 */
    UNACTIVATED,
    /** 服务端确认有效 */
    ACTIVE,
    /** 服务端暂不可达，但在宽限期内，继续放行 */
    GRACE,
    /** 服务端明确拒绝：到期或次数用完 */
    EXPIRED,
    /** 服务端明确拒绝：作废 / token 无效 */
    REVOKED,
    /** 服务端不可达且已超过宽限期 */
    NETWORK_BLOCKED
}
