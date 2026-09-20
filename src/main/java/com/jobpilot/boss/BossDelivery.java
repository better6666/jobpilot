package com.jobpilot.boss;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 投递记录。一个岗位一条（platform + encrypt_id + encrypt_user_id 唯一），
 * 状态流转：预演/未投递 → 已投递 / 投递失败 / 已过滤。
 */
@Data
@TableName("deliveries")
public class BossDelivery {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 平台标识，固定 boss；P2 加平台时同表复用 */
    private String platform;
    /** 触发本次投递的搜索关键词 */
    private String keyword;
    private String encryptId;
    private String encryptUserId;
    private String jobName;
    private String brandName;
    private String salaryDesc;
    private String cityName;
    private String areaDistrict;
    private String jobExperience;
    private String jobDegree;
    private String bossName;
    private String bossTitle;
    private String jobUrl;
    /** 实际发送的打招呼语（预演时为将要发送的话术） */
    private String greeting;
    /** 预演 / 未投递 / 已投递 / 已过滤 / 投递失败 */
    private String deliveryStatus;
    private String failReason;
    /** 打分结果（已过滤时记录分数便于复盘规则） */
    private Integer score;
    private String createdAt;
    private String updatedAt;
}
