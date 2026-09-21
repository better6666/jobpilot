package com.jobpilot.delivery;

/**
 * 岗位卡片的平台无关视图。
 *
 * 四个平台的字段来源完全不同——Boss 和猎聘拦列表接口拿 JSON，51job 拦
 * 搜索接口，智联只能爬 DOM——但落到 deliveries 表里是同一套列。这里把
 * 那张表需要的字段抽成接口，各平台自己的卡片类实现它，编排层
 * （{@link DeliveryService}）就只需要认这一套 getter。
 *
 * 命名沿用 P1 的 Boss 字段名（encryptId/encryptUserId 在表里叫 encrypt_id），
 * 没有另造一套：老用户库里已有的记录和这套名字是对得上的。
 */
public interface JobCard {

    /** 平台内唯一的岗位标识，落 encrypt_id 列，也是去重键的一半 */
    String getJobId();

    /** HR / 招聘者标识，落 encrypt_user_id 列；智联这类采不到的返回 null */
    String getBossId();

    String getJobName();

    String getBrandName();

    String getSalaryDesc();

    String getCityName();

    String getAreaDistrict();

    String getJobExperience();

    String getJobDegree();

    String getBossName();

    String getBossTitle();

    /** 职位详情页地址 */
    String getJobUrl();

    /** 职位描述全文，打分规则的 jdRules 用；采不到返回 null */
    String getPostDescription();

    String getIndustryName();

    String getBrandScaleName();

    /** HR 活跃描述（Boss 的"3年前活跃"这类），平台没有这个概念返回 null */
    String getBossActiveTimeDesc();
}
