package com.jobpilot.boss;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 打分结果。reason 记录命中的规则，便于用户复盘"为什么这个岗位没投/投了"。 */
@Data
@AllArgsConstructor
public class ScoreResult {

    private final int score;
    private final boolean pass;
    private final String reason;
}
