package com.jobpilot.delivery;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 四个平台共有的运行配置。各平台自己的配置类继承它，再加上平台特有的筛选项
 * （Boss 有职位类型/融资阶段，猎聘只有薪资，51job 和智联连薪资码表都不一样）。
 *
 * 存 config 表（config_key = 平台名），管理页改完即生效，不用重启。
 */
@Data
public class PlatformConfig {

    /** 搜索关键词，逐个跑 */
    private List<String> keywords = new ArrayList<>();

    /** 城市名或城市码，启动时统一换成码；空 = 不限 */
    private String city = "";

    /** 薪资筛选项，值为平台自己的码，"0"/空 = 不限 */
    private String salary = "";

    /**
     * 我的最高学历，存中文档位名（大专/本科/…），空 = 不设限。
     * 各平台的 URL 筛选挡不住"要求高于我"的岗位，这一项专管那个，见 {@link RequirementFilter}
     */
    private String myDegree = "";

    /** 我的工作经验，存中文档位名（应届生/1-3年/…），空 = 不设限 */
    private String myExperience = "";

    /** 每个关键词最多处理多少个岗位（防一次跑太久触发风控） */
    private int maxJobsPerKeyword = 50;

    /** 两个岗位之间的随机间隔秒数（实际取 [waitSeconds/2, waitSeconds]） */
    private int waitSeconds = 10;

    /**
     * 固定话术。Boss 是发消息给 HR；其余平台是简历投递，用不上，
     * 留着是因为猎聘的 IM 里还能补一句追问，届时复用。
     */
    private String sayHi = "";

    /** true = 只走流程不真投，记录落库状态为"预演" */
    private boolean dryRun = true;

    /** 扫码登录等待分钟数 */
    private int loginTimeoutMinutes = 5;

    /**
     * 打分规则，四个平台共用一套。见 {@link ScoreRules}。
     * 默认全空 + threshold 0 = 不拦任何岗位。
     */
    private ScoreRules scoreRules = new ScoreRules();
}
