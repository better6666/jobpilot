package com.jobpilot.boss;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jobpilot.browser.BrowserManager;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Boss 投递编排：登录 → 逐关键词采集 → 打分过滤 → 投递 → 落库。
 *
 * 整个跑批是一个提交给 BrowserManager 的异步任务（单线程里顺序执行），
 * 状态机 + 计数器暴露给管理页轮询。停止是协作式的：stop() 只置标志，
 * 采集/投递循环每个间隙检查，最长一个动作周期内退出。
 */
@Slf4j
@Service
public class BossService {

    private static final int MAX_LOG_LINES = 200;

    private final BossProperties properties;
    private final BossDriver driver;
    private final BossDeliveryMapper deliveryMapper;
    private final BrowserManager browserManager;
    private final BossOptions options;

    private volatile RunStatus status = RunStatus.idle();
    private volatile boolean stopRequested;
    private Future<?> currentRun;

    public BossService(BossProperties properties, BossDriver driver, BossDeliveryMapper deliveryMapper,
                       BrowserManager browserManager, BossOptions options) {
        this.properties = properties;
        this.driver = driver;
        this.deliveryMapper = deliveryMapper;
        this.browserManager = browserManager;
        this.options = options;
    }

    // ------------------------------------------------------------------
    // 对外 API
    // ------------------------------------------------------------------

    /** 启动跑批。配置不合法（没关键词）直接返回错误，不进浏览器。 */
    public synchronized String start() {
        if (status.getState() == RunState.RUNNING) {
            return "已有投递任务在运行";
        }
        BossProperties.BossConfig config = properties.get();
        List<String> keywords = config.getKeywords() == null ? List.of() : config.getKeywords().stream()
                .filter(k -> k != null && !k.isBlank()).toList();
        if (keywords.isEmpty()) {
            return "请先配置至少一个搜索关键词";
        }
        if (BossSearchUrl.resolveCityCode(options, config.getCity()) == null
                && config.getCity() != null && !config.getCity().isBlank()) {
            return "城市「" + config.getCity() + "」无法识别，请填城市名（如：上海）或城市码";
        }
        stopRequested = false;
        status = RunStatus.running(keywords.size(), config.isDryRun());
        appendLog("投递任务启动：" + String.join("、", keywords)
                + (config.isDryRun() ? "（预演模式，不会真发消息）" : ""));
        currentRun = browserManager.submitAsync(() -> runJob(keywords, config));
        return null;
    }

    public synchronized void stop() {
        if (status.getState() != RunState.RUNNING) {
            return;
        }
        stopRequested = true;
        status.setState(RunState.STOPPING);
        appendLog("收到停止指令，将在当前动作完成后退出");
    }

    public RunStatus status() {
        return status;
    }

    public List<BossDelivery> recentDeliveries(int limit) {
        try {
            return deliveryMapper.selectList(new LambdaQueryWrapper<BossDelivery>()
                    .orderByDesc(BossDelivery::getId)
                    .last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
        } catch (Exception e) {
            log.warn("读取投递记录失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 清空投递记录（用户改完规则想重跑时用） */
    public int clearDeliveries() {
        try {
            return deliveryMapper.delete(null);
        } catch (Exception e) {
            log.warn("清空投递记录失败: {}", e.getMessage());
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 跑批主流程（跑在浏览器 dispatcher 线程上）
    // ------------------------------------------------------------------

    private void runJob(List<String> keywords, BossProperties.BossConfig config) {
        String cityCode = BossSearchUrl.resolveCityCode(options, config.getCity());
        try {
            appendLog("正在打开浏览器并检查登录态…");
            BossDriver.LoginResult login = driver.ensureLogin(config.getLoginTimeoutMinutes(),
                    this::appendLog, () -> stopRequested);
            if (login != BossDriver.LoginResult.LOGGED_IN) {
                finish("登录超时或已取消，请重试（扫码要在 " + config.getLoginTimeoutMinutes() + " 分钟内完成）");
                return;
            }
            status.setLoggedIn(true);
            appendLog("登录态就绪，开始采集岗位");

            int consecutiveFailures = 0;
            for (int k = 0; k < keywords.size(); k++) {
                if (stopRequested) {
                    break;
                }
                String keyword = keywords.get(k);
                status.setKeywordIndex(k + 1);
                status.setCurrentKeyword(keyword);
                String searchUrl = BossSearchUrl.build(cityCode, keyword, config);
                appendLog("【" + keyword + "】打开搜索页");

                driver.processKeyword(searchUrl, keyword, config.getMaxJobsPerKeyword(),
                        this::appendLog, () -> stopRequested,
                        (card, listPage) -> {
                            processCard(card, keyword, config, listPage);
                            // 节奏控制：随机 [wait/2, wait]，固定间隔是机器行为最明显的特征
                            int waitMs = 500 + ThreadLocalRandom.current()
                                    .nextInt(Math.max(1, config.getWaitSeconds()) * 500);
                            sleep(waitMs);
                        });
                if (stopRequested) {
                    break;
                }
            }
            if (stopRequested) {
                finish("已手动停止。本次共投递 " + status.getDelivered() + " 个岗位");
            } else {
                finish("全部关键词处理完成。共投递 " + status.getDelivered()
                        + " 个，过滤 " + status.getFiltered() + " 个，失败 " + status.getFailed() + " 个");
            }
        } catch (Exception e) {
            log.error("投递任务异常终止", e);
            finish("任务异常终止: " + e.getMessage());
        }
    }

    void processCard(BossJobCard card, String keyword, BossProperties.BossConfig config,
                             com.microsoft.playwright.Page listPage) {
        String target = brief(card);
        status.setScanned(status.getScanned() + 1);
        try {
            // 去重：已投递过的不碰；预演只是模拟发送，真实投递不该被它挡住，
            // 投递失败的也允许重试（更新原记录）
            BossDelivery existing = findExisting(card);
            if (existing != null && "已投递".equals(existing.getDeliveryStatus())) {
                status.setSkipped(status.getSkipped() + 1);
                appendLog("跳过 | " + target + " | 记录里已有（" + existing.getDeliveryStatus() + "）");
                return;
            }

            // HR 活跃度过滤（可选开关）：活跃描述带"年"的大概率不看了
            if (config.isFilterInactiveHr() && card.getBossActiveTimeDesc() != null
                    && card.getBossActiveTimeDesc().contains("年")) {
                record(card, keyword, "已过滤", "HR 不活跃: " + card.getBossActiveTimeDesc(), null, null, existing);
                status.setFiltered(status.getFiltered() + 1);
                appendLog("过滤 | " + target + " | HR 不活跃");
                return;
            }

            BossScorer scorer = new BossScorer(config.getScoreRules());
            ScoreResult scoreResult = scorer.score(card);
            if (!scoreResult.isPass()) {
                record(card, keyword, "已过滤", scoreResult.getReason(), scoreResult.getScore(), null, existing);
                status.setFiltered(status.getFiltered() + 1);
                appendLog("过滤 | " + target + " | " + scoreResult.getReason()
                        + " | 得分 " + scoreResult.getScore());
                return;
            }

            appendLog("投递中 | " + target + " | 得分 " + scoreResult.getScore() + " | " + scoreResult.getReason());
            BossDriver.DeliveryOutcome outcome = driver.deliver(listPage, card,
                    config.getSayHi(), config.isDryRun(), this::appendLog, () -> stopRequested);

            switch (outcome.status()) {
                case DELIVERED -> {
                    record(card, keyword, "已投递", null, scoreResult.getScore(), outcome.greeting(), existing);
                    status.setDelivered(status.getDelivered() + 1);
                }
                case PREVIEW -> {
                    record(card, keyword, "预演", null, scoreResult.getScore(), outcome.greeting(), existing);
                    status.setPreviewed(status.getPreviewed() + 1);
                }
                case LIMIT -> {
                    record(card, keyword, "投递失败", "触发每日沟通上限", scoreResult.getScore(), null, existing);
                    status.setFailed(status.getFailed() + 1);
                    appendLog("触发 Boss 每日沟通上限，任务停止");
                    stopRequested = true;
                }
                case FAILED -> {
                    record(card, keyword, "投递失败", outcome.failReason(), scoreResult.getScore(), null, existing);
                    status.setFailed(status.getFailed() + 1);
                    appendLog("投递失败 | " + target + " | " + outcome.failReason());
                }
            }
        } catch (Exception e) {
            log.warn("处理岗位异常 | {}: {}", target, e.getMessage());
            status.setFailed(status.getFailed() + 1);
        }
    }

    private BossDelivery findExisting(BossJobCard card) {
        return deliveryMapper.selectOne(new LambdaQueryWrapper<BossDelivery>()
                .eq(BossDelivery::getPlatform, "boss")
                .eq(BossDelivery::getEncryptId, card.getEncryptId())
                .eq(BossDelivery::getEncryptUserId, card.getEncryptUserId())
                .last("LIMIT 1"));
    }

    /**
     * 落库。同一岗位重复出现时更新原记录而不是插新的——
     * (platform, encrypt_id, encrypt_user_id) 上有唯一索引，插第二条会直接冲突。
     */
    private BossDelivery record(BossJobCard card, String keyword, String deliveryStatus,
                                String failReason, Integer score, String greeting, BossDelivery existing) {
        BossDelivery record = existing != null ? existing : new BossDelivery();
        boolean isNew = existing == null;
        record.setPlatform("boss");
        record.setKeyword(keyword);
        record.setEncryptId(card.getEncryptId());
        record.setEncryptUserId(card.getEncryptUserId());
        record.setJobName(card.getJobName());
        record.setBrandName(card.getBrandName());
        record.setSalaryDesc(card.getSalaryDesc());
        record.setCityName(card.getLocationName());
        record.setAreaDistrict(card.getAreaDistrict());
        record.setJobExperience(card.getExperienceName());
        record.setJobDegree(card.getDegreeName());
        record.setBossName(card.getBossName());
        record.setBossTitle(card.getBossTitle());
        record.setJobUrl(card.jobUrl());
        record.setDeliveryStatus(deliveryStatus);
        record.setFailReason(failReason);
        record.setScore(score);
        record.setGreeting(greeting);
        record.setUpdatedAt(Instant.now().toString());
        if (isNew) {
            record.setCreatedAt(record.getUpdatedAt());
        }
        if (isNew) {
            deliveryMapper.insert(record);
        } else {
            deliveryMapper.updateById(record);
        }
        return record;
    }

    private void finish(String message) {
        status.setState(RunState.FINISHED);
        status.setMessage(message);
        status.setFinishedAt(Instant.now().toString());
        appendLog(message);
    }

    private void appendLog(String message) {
        String line = Instant.now().toString().substring(11, 19) + " " + message;
        status.getLogs().add(line);
        while (status.getLogs().size() > MAX_LOG_LINES) {
            status.getLogs().remove(0);
        }
        log.info("[boss] {}", message);
    }

    private static String brief(BossJobCard card) {
        return (card.getBrandName() != null ? card.getBrandName() : "?")
                + " | " + (card.getJobName() != null ? card.getJobName() : "?")
                + (card.getSalaryDesc() != null ? " | " + card.getSalaryDesc() : "");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        stopRequested = true;
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    public enum RunState {
        IDLE, RUNNING, STOPPING, FINISHED
    }

    @Data
    public static class RunStatus {
        private RunState state = RunState.IDLE;
        private String message = "";
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

        static RunStatus idle() {
            RunStatus status = new RunStatus();
            status.setMessage("空闲");
            return status;
        }

        static RunStatus running(int totalKeywords, boolean dryRun) {
            RunStatus status = new RunStatus();
            status.setState(RunState.RUNNING);
            status.setMessage("运行中");
            status.setTotalKeywords(totalKeywords);
            status.setDryRun(dryRun);
            status.setStartedAt(Instant.now().toString());
            return status;
        }
    }
}
