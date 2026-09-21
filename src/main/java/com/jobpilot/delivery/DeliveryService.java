package com.jobpilot.delivery;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jobpilot.ai.GreetingService;
import com.jobpilot.browser.BrowserManager;
import com.microsoft.playwright.Page;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 投递编排的公共部分：登录 → 逐关键词采集 → 过滤 → 投递 → 落库 → 状态机。
 *
 * <p>平台相关的一切都抽成抽象方法，由各平台的 Service 提供：
 * <pre>
 *   platform()          平台标识（落库用）
 *   config()            当前配置
 *   validate()          启动前的配置校验（城市认不认得出来）
 *   collect()           采集一个关键词（每张卡片回调一次）
 *   filter()            打分 + 过滤判定
 *   deliver()           投递一个岗位
 *   limitReachedOnPage() 页面是否提示今日上限
 * </pre>
 *
 * <p>为什么值得抽这一层：四个平台的采集和投递机制完全不同（Boss 拦详情接口 +
 * 聊天，猎聘拦搜索接口 + IM overlay，51job 列表页直接点投递，智联点卡片出详情
 * 面板再投），但"跑批是一个提交给单线程 dispatcher 的异步任务、状态机 + 计数器
 * 暴露给管理页、停止是协作式的、按 (platform, jobId, bossId) 去重后落库"这套
 * 骨架一模一样。抄四遍的代价不是写的时候，是以后改一处要记得改四处。
 *
 * <p>线程模型：整个跑批跑在 BrowserManager 的 dispatcher 线程上（单线程），
 * 所以抽象方法里可以随便摸 page，不用担心并发。
 */
@Slf4j
public abstract class DeliveryService<C extends JobCard> {

    private static final int MAX_LOG_LINES = 200;

    private final DeliveryMapper mapper;
    private final BrowserManager browserManager;
    private final RunCoordinator coordinator;
    private final GreetingService greetingService;

    private volatile RunStatus status = RunStatus.idle();
    private volatile boolean stopRequested;
    private Future<?> currentRun;

    protected DeliveryService(DeliveryMapper mapper, BrowserManager browserManager,
                              RunCoordinator coordinator, GreetingService greetingService) {
        this.mapper = mapper;
        this.browserManager = browserManager;
        this.coordinator = coordinator;
        this.greetingService = greetingService;
    }

    // ------------------------------------------------------------------
    // 子类必须提供
    // ------------------------------------------------------------------

    /** 平台标识，落 deliveries.platform 列：boss / liepin / job51 / zhilian */
    protected abstract String platform();

    /** 平台展示名，日志和管理页标题用 */
    protected abstract String displayName();

    protected abstract PlatformConfig config();

    /** 启动前的配置校验。返回 null = 通过；返回非 null = 错误信息，直接返回给用户 */
    protected abstract String validate(PlatformConfig config);

    /** 城市码；配置里城市为空返回 null（全国） */
    protected abstract String cityCode(PlatformConfig config);

    /** 搜索页 URL */
    protected abstract String searchUrl(String cityCode, String keyword, PlatformConfig config);

    protected abstract LoginResult ensureLogin(PlatformConfig config, ProgressListener listener,
                                               java.util.function.BooleanSupplier stop);

    /** 采集一个关键词：进搜索页 → 翻页/滚动 → 每张卡片回调一次 */
    protected abstract void collect(String keyword, String searchUrl, int maxCards,
                                    ProgressListener listener, java.util.function.BooleanSupplier stop,
                                    CardConsumer<C> consumer);

    /** 打分 + 过滤判定 */
    protected abstract FilterResult filter(C card, PlatformConfig config);

    /** 投递一个岗位。单个岗位失败不抛异常，返回 FAILED 让编排层继续 */
    protected abstract DeliveryOutcome deliver(C card, Page listPage, PlatformConfig config,
                                               ProgressListener listener,
                                               java.util.function.BooleanSupplier stop);

    /** 页面是否提示今日投递/沟通上限：命中就必须停，继续点只会全量失败 */
    protected boolean limitReachedOnPage(Page page) {
        return false;
    }

    // ------------------------------------------------------------------
    // 话术
    // ------------------------------------------------------------------

    /**
     * 一个岗位要发的话术：AI 开着就按 JD 现生成，否则用配置里的固定话术。
     *
     * <p>放在基类而不是各平台的 deliver() 里，是因为"人设 / 中转站地址 /
     * 去重 / 兜底"这套逻辑四平台共用，抄四遍的后果是以后改一处忘三处。
     * 现在只有 Boss（打招呼语）和猎聘（IM 追问）真会用到。
     */
    protected GreetingService.Greeting greetingFor(C card, PlatformConfig config) {
        return greetingService.compose(card, config.getSayHi());
    }

    /** 猎聘 IM 里的追问句 */
    protected GreetingService.Greeting followUpFor(C card, PlatformConfig config) {
        return greetingService.composeFollowUp(card, config.getSayHi());
    }

    // ------------------------------------------------------------------
    // 对外 API
    // ------------------------------------------------------------------

    /** 启动跑批。配置不合法直接返回错误信息，不进浏览器。 */
    public synchronized String start() {
        if (status.getState() == RunStatus.RunState.RUNNING
                || status.getState() == RunStatus.RunState.STOPPING) {
            return "已有投递任务在运行";
        }
        PlatformConfig config = config();
        List<String> keywords = config.getKeywords() == null ? List.of()
                : config.getKeywords().stream().filter(k -> k != null && !k.isBlank()).toList();
        if (keywords.isEmpty()) {
            return "请先配置至少一个搜索关键词";
        }
        String error = validate(config);
        if (error != null) {
            return error;
        }
        // 四个平台共用一个浏览器上下文，同一时刻只允许一个在跑
        String holder = coordinator.acquire(platform());
        if (holder != null) {
            return "「" + holder + "」正在投递，请先等它结束";
        }
        stopRequested = false;
        status = RunStatus.running(keywords.size(), config.isDryRun(), displayName());
        appendLog("投递任务启动：" + String.join("、", keywords)
                + (config.isDryRun() ? "（预演模式，不会真发消息）" : ""));
        try {
            currentRun = browserManager.submitAsync(() -> {
                try {
                    runJob(keywords, config);
                } finally {
                    // 不管正常结束还是抛异常，运行权都必须还回去，否则这个平台
                    // 再也起不来了（start() 会一直返回"已有任务在运行"）
                    coordinator.release(platform());
                }
            });
        } catch (Exception e) {
            coordinator.release(platform());
            status = RunStatus.idle();
            log.error("启动跑批失败", e);
            return "启动失败: " + e.getMessage();
        }
        return null;
    }

    public synchronized void stop() {
        if (status.getState() != RunStatus.RunState.RUNNING) {
            return;
        }
        stopRequested = true;
        status.setState(RunStatus.RunState.STOPPING);
        appendLog("收到停止指令，将在当前动作完成后退出");
    }

    public RunStatus status() {
        return status;
    }

    public List<Delivery> recentDeliveries(int limit) {
        try {
            return mapper.selectList(new LambdaQueryWrapper<Delivery>()
                    .eq(Delivery::getPlatform, platform())
                    .orderByDesc(Delivery::getId)
                    .last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
        } catch (Exception e) {
            log.warn("读取投递记录失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 清空本平台的投递记录（用户改完规则想重跑时用） */
    public int clearDeliveries() {
        try {
            return mapper.delete(new LambdaQueryWrapper<Delivery>()
                    .eq(Delivery::getPlatform, platform()));
        } catch (Exception e) {
            log.warn("清空投递记录失败: {}", e.getMessage());
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 跑批主流程（跑在浏览器 dispatcher 线程上）
    // ------------------------------------------------------------------

    private void runJob(List<String> keywords, PlatformConfig config) {
        try {
            appendLog("正在打开浏览器并检查登录态…");
            LoginResult login = ensureLogin(config, this::appendLog, () -> stopRequested);
            if (login != LoginResult.LOGGED_IN) {
                finish("登录超时或已取消，请重试（扫码要在 " + config.getLoginTimeoutMinutes() + " 分钟内完成）");
                return;
            }
            status.setLoggedIn(true);
            appendLog("登录态就绪，开始采集岗位");
            String ai = greetingService.describe();
            if (ai != null) {
                appendLog(ai);
            }

            String city = cityCode(config);
            for (int k = 0; k < keywords.size(); k++) {
                if (stopRequested) {
                    break;
                }
                String keyword = keywords.get(k);
                status.setKeywordIndex(k + 1);
                status.setCurrentKeyword(keyword);
                String searchUrl = searchUrl(city, keyword, config);
                appendLog("【" + keyword + "】打开搜索页");

                collect(keyword, searchUrl, config.getMaxJobsPerKeyword(),
                        this::appendLog, () -> stopRequested,
                        (card, listPage) -> {
                            processCard(card, keyword, config, listPage);
                            pace(config);
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

    /**
     * 处理一个岗位：去重 → 过滤/打分 → 投递 → 落库。
     * 包成 protected 是为了能单测：mock 掉 mapper 和适配器，不碰浏览器。
     */
    protected void processCard(C card, String keyword, PlatformConfig config, Page listPage) {
        String target = brief(card);
        status.setScanned(status.getScanned() + 1);
        try {
            // 去重：已投递过的不碰；预演只是模拟，真实投递不该被它挡住，
            // 投递失败的也允许重试（更新原记录）
            Delivery existing = findExisting(card);
            if (existing != null && "已投递".equals(existing.getDeliveryStatus())) {
                status.setSkipped(status.getSkipped() + 1);
                appendLog("跳过 | " + target + " | 记录里已有（" + existing.getDeliveryStatus() + "）");
                return;
            }

            FilterResult verdict = filter(card, config);
            if (verdict.rejectReason() != null) {
                record(card, keyword, "已过滤", verdict.rejectReason(), verdict.score(), null, existing);
                status.setFiltered(status.getFiltered() + 1);
                appendLog("过滤 | " + target + " | " + verdict.rejectReason());
                return;
            }

            Integer score = verdict.score();
            appendLog("投递中 | " + target + (score != null ? " | 得分 " + score : ""));
            DeliveryOutcome outcome = deliver(card, listPage, config, this::appendLog, () -> stopRequested);

            switch (outcome.status()) {
                case DELIVERED -> {
                    record(card, keyword, "已投递", null, score, outcome.greeting(), existing);
                    status.setDelivered(status.getDelivered() + 1);
                }
                case PREVIEW -> {
                    record(card, keyword, "预演", null, score, outcome.greeting(), existing);
                    status.setPreviewed(status.getPreviewed() + 1);
                }
                case LIMIT -> {
                    record(card, keyword, "投递失败", "触发每日投递上限", score, null, existing);
                    status.setFailed(status.getFailed() + 1);
                    appendLog("触发每日投递上限，任务停止");
                    stopRequested = true;
                }
                case FAILED -> {
                    record(card, keyword, "投递失败", outcome.failReason(), score, null, existing);
                    status.setFailed(status.getFailed() + 1);
                    appendLog("投递失败 | " + target + " | " + outcome.failReason());
                }
            }
        } catch (Exception e) {
            log.warn("处理岗位异常 | {}: {}", target, e.getMessage());
            status.setFailed(status.getFailed() + 1);
        }
    }

    /**
     * 节奏控制：随机 [wait/2, wait]，固定间隔是机器行为最明显的特征。
     * 顺带在停顿里看一眼页面有没有弹上限提示——51job 和智联的上限 toast
     * 只存在一两秒，投递完立刻探最容易抓到。
     */
    private void pace(PlatformConfig config) {
        int waitMs = 500 + ThreadLocalRandom.current()
                .nextInt(Math.max(1, config.getWaitSeconds()) * 500);
        sleep(waitMs);
    }

    protected Delivery findExisting(C card) {
        return mapper.selectOne(new LambdaQueryWrapper<Delivery>()
                .eq(Delivery::getPlatform, platform())
                .eq(Delivery::getEncryptId, card.getJobId())
                .eq(Delivery::getEncryptUserId, card.getBossId())
                .last("LIMIT 1"));
    }

    /**
     * 落库。同一岗位重复出现时更新原记录而不是插新的——
     * (platform, encrypt_id, encrypt_user_id) 上有唯一索引，插第二条会直接冲突。
     */
    protected Delivery record(C card, String keyword, String deliveryStatus,
                              String failReason, Integer score, String greeting, Delivery existing) {
        Delivery record = existing != null ? existing : new Delivery();
        boolean isNew = existing == null;
        record.setPlatform(platform());
        record.setKeyword(keyword);
        record.setEncryptId(card.getJobId());
        record.setEncryptUserId(card.getBossId());
        record.setJobName(card.getJobName());
        record.setBrandName(card.getBrandName());
        record.setSalaryDesc(card.getSalaryDesc());
        record.setCityName(card.getCityName());
        record.setAreaDistrict(card.getAreaDistrict());
        record.setJobExperience(card.getJobExperience());
        record.setJobDegree(card.getJobDegree());
        record.setBossName(card.getBossName());
        record.setBossTitle(card.getBossTitle());
        record.setJobUrl(card.getJobUrl());
        record.setDeliveryStatus(deliveryStatus);
        record.setFailReason(failReason);
        record.setScore(score);
        record.setGreeting(greeting);
        record.setUpdatedAt(Instant.now().toString());
        if (isNew) {
            record.setCreatedAt(record.getUpdatedAt());
            mapper.insert(record);
        } else {
            mapper.updateById(record);
        }
        return record;
    }

    private void finish(String message) {
        status.setState(RunStatus.RunState.FINISHED);
        status.setMessage(message);
        status.setFinishedAt(Instant.now().toString());
        appendLog(message);
    }

    protected void appendLog(String message) {
        String line = Instant.now().toString().substring(11, 19) + " " + message;
        status.getLogs().add(line);
        while (status.getLogs().size() > MAX_LOG_LINES) {
            status.getLogs().remove(0);
        }
        log.info("[{}] {}", platform(), message);
    }

    protected static String brief(JobCard card) {
        return (card.getBrandName() != null ? card.getBrandName() : "?")
                + " | " + (card.getJobName() != null ? card.getJobName() : "?")
                + (card.getSalaryDesc() != null ? " | " + card.getSalaryDesc() : "");
    }

    protected static void sleep(long ms) {
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
}
