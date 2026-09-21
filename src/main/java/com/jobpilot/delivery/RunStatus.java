package com.jobpilot.delivery;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 跑批状态。管理页每 3 秒轮询一次，字段名和前端是对好的，别改。
 */
@Data
public class RunStatus {

    public enum RunState {
        IDLE, RUNNING, STOPPING, FINISHED
    }

    private RunState state = RunState.IDLE;
    private String message = "";
    /** 平台展示名，管理页标题用 */
    private String platform = "";
    private String currentKeyword = "";
    private int keywordIndex;
    private int totalKeywords;
    private boolean dryRun;
    private boolean loggedIn;
    private int scanned;
    private int delivered;
    private int filtered;
    private int failed;
    private int previewed;
    private int skipped;
    private String startedAt;
    private String finishedAt;
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());

    public static RunStatus idle() {
        RunStatus status = new RunStatus();
        status.setMessage("空闲");
        return status;
    }

    public static RunStatus running(int totalKeywords, boolean dryRun, String platform) {
        RunStatus status = new RunStatus();
        status.setState(RunState.RUNNING);
        status.setMessage("运行中");
        status.setTotalKeywords(totalKeywords);
        status.setDryRun(dryRun);
        status.setPlatform(platform);
        status.setStartedAt(Instant.now().toString());
        return status;
    }
}
