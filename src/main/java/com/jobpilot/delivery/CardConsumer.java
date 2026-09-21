package com.jobpilot.delivery;

import com.microsoft.playwright.Page;

/**
 * 采集到一个岗位时的回调。在 dispatcher 线程内同步调用，
 * listPage 就是列表页——Boss 要留着它去点下一个卡片，
 * 51job 和智联的投递按钮也都在这一页上。
 */
@FunctionalInterface
public interface CardConsumer<C extends JobCard> {
    void accept(C card, Page listPage);
}
