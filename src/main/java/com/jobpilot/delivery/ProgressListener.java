package com.jobpilot.delivery;

/** 进度回调：消息走日志 + 管理页。实现方只做轻量的事，别在这里摸浏览器 */
public interface ProgressListener {
    void onProgress(String message);
}
