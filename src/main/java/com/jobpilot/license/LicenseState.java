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
    /**
     * token 是对的，但设备指纹和激活时不一致。
     *
     * <p>绝大多数情况是「把本机数据目录拷到了另一台机器」——早先客户端上行发的是
     * 库里的旧指纹，拷过去照样匹配；现在改成实时计算，换机器立刻对不上。
     * 也可能是用户自己换了电脑、改了主机名或用户名。需要重新激活。
     */
    DEVICE_MISMATCH,
    /** 服务端不可达且已超过宽限期 */
    NETWORK_BLOCKED
}
