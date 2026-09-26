package com.jobpilot.delivery;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jobpilot.ai.GreetingService;
import com.jobpilot.browser.BrowserManager;
import com.jobpilot.license.LicenseService;
import com.microsoft.playwright.Page;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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
    private final LicenseService licenseService;
    private final com.jobpilot.license.EntitlementService entitlementService;

    private volatile RunStatus status = RunStatus.idle();
    private volatile boolean stopRequested;
    private volatile boolean stoppedForHour;
    /** 已经因为"卡密不能用"停过一次了，避免每张卡片都打一行日志 */
    private volatile boolean stoppedForLicense;
    /** 已经因为"当日投递额度用完"停过一次 */
    private volatile boolean stoppedForQuota;
    private Future<?> currentRun;

    protected DeliveryService(DeliveryMapper mapper, BrowserManager browserManager,
                              RunCoordinator coordinator, GreetingService greetingService,
                              LicenseService licenseService,
                              com.jobpilot.license.EntitlementService entitlementService) {
        this.mapper = mapper;
        this.browserManager = browserManager;
        this.coordinator = coordinator;
        this.greetingService = greetingService;
        this.licenseService = licenseService;
        this.entitlementService = entitlementService;
    }

    // ------------------------------------------------------------------
    // 子类必须提供
    // ------------------------------------------------------------------

    /** 平台标识，落 deliveries.platform 列：boss / liepin / job51 / zhilian / shixiseng */
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
        // 额度已经用完时别进浏览器：以前会照常打开、登录态检测、扫到第一个岗位
        // 才静默停下，最后的日志是"已手动停止。本次共投递 0 个岗位"，
        // 用户读到的是"登录了却不投"。
        String quotaError = quotaPreCheck(config);
        if (quotaError != null) {
            return quotaError;
        }
        String greetingError = greetingPreCheck(config);
        if (greetingError != null) {
            return greetingError;
        }
        // 次数卡用完时启动也是白跑
        if (licenseService.exhausted()) {
            return "卡密已不能使用（次数已用完或已到期），请重新激活后再投递";
        }
        // 四个平台共用一个浏览器上下文，同一时刻只允许一个在跑
        String holder = coordinator.acquire(platform());
        if (holder != null) {
            return "「" + holder + "」正在投递，请先等它结束";
        }
        stopRequested = false;
        stoppedForHour = false;
        stoppedForLicense = false;
        stoppedForQuota = false;
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
        // 额度是"登录了却不投"最容易误判的一环，管理页每次轮询都带回本平台的真实余量
        RunStatus current = status;
        if (entitlementService != null) {
            current.setQuotaLimit(entitlementService.current().quota("max_daily_apply"));
            current.setQuotaUsed(entitlementService.usedToday(applyCounterKey()));
        }
        return current;
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
                            try {
                                processCard(card, keyword, config, listPage);
                            } catch (Exception e) {
                                status.setFailed(status.getFailed() + 1);
                                appendLog("处理岗位失败 | " + brief(card) + " | " + e.getMessage());
                                log.warn("[{}] 处理岗位失败", platform(), e);
                            } finally {
                                pace(config);
                            }
                        });
                if (stopRequested) {
                    break;
                }
            }
            if (stoppedForQuota) {
                // 额度闸停机时已经把原因写进日志和 message 了，别再覆盖成"已手动停止"
                finishState();
            } else if (stoppedForHour) {
                finish(hourlyLimitMessage());
            } else if (stopRequested) {
                finish("已手动停止。本次共投递 " + status.getDelivered() + " 个岗位");
            } else if (status.getScanned() == 0) {
                finish("未读取到可处理的岗位，本次没有投递。请检查关键词、平台登录态和招聘网站页面");
            } else {
                finish("全部关键词处理完成。共扫描 " + status.getScanned()
                        + " 个，投递 " + status.getDelivered() + " 个，预演 "
                        + status.getPreviewed() + " 个，过滤 " + status.getFiltered()
                        + " 个，失败 " + status.getFailed() + " 个");
            }
        } catch (Exception e) {
            log.error("投递任务异常终止", e);
            finish("任务异常终止: " + e.getMessage());
        }
    }

    /**
     * 当日投递额度的计数键：一个平台一个键。
     *
     * <p>原来五平台共用 {@code apply} 一个计数器，结果是 Boss 一家就能把进阶版的
     * 300 吃满，猎聘/智联当天再怎么登录都是空跑——用户看到的就是"投不了简历"。
     * 招聘平台的风控本来就是按平台各算一套，额度口径跟着按平台分才讲得通。
     */
    private String applyCounterKey() {
        return "apply:" + platform();
    }

    /**
     * 每平台每小时最多投这么多。
     *
     * <p>日额度抬到 300 之后，把账号送进风控的不是总量而是<em>密度</em>：两个岗位
     * 之间只随机等十秒，一小时理论上能发两百多个。40 个约等于一分钟一个。
     */
    static final int HOURLY_LIMIT = 40;

    /** 本小时的计数桶。键自带小时，整点一换就是全新的一行，不用清库也不用定时任务 */
    private String hourCounterKey() {
        return applyCounterKey() + ":" + LocalDateTime.now().format(HOUR_KEY_FORMAT);
    }

    private static final DateTimeFormatter HOUR_KEY_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final DateTimeFormatter HOUR_READABLE =
            DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 本小时还有余量。
     *
     * <p>计数走本地 {@code usage_counter} 而不是卡密服务端，因为这道闸是保护用户
     * 账号的，跟授权无关——自用模式（{@code license.enabled=false}，日额度不限）照样要限。
     *
     * <p>满了就收工而不是原地等下一小时：任务挂在那儿一小时，浏览器一直开着、
     * 全局单跑锁也一直占着，别的平台这段时间一个都投不了。
     */
    private boolean hourlyWindowOpen() {
        return entitlementService == null
                || entitlementService.usedToday(hourCounterKey()) < HOURLY_LIMIT;
    }

    private String hourlyLimitMessage() {
        return "「" + displayName() + "」本小时已投满 " + HOURLY_LIMIT + " 个，本次先收工。"
                + LocalTime.now().plusHours(1).withMinute(0).format(HOUR_READABLE)
                + "（下一个整点）之后重新点开始就继续——发得太密容易被平台风控。";
    }

    /**
     * 处理一个岗位：去重 → 过滤/打分 → 投递 → 落库。
     * 包成 protected 是为了能单测：mock 掉 mapper 和适配器，不碰浏览器。
     */
    protected void processCard(C card, String keyword, PlatformConfig config, Page listPage) {
        // 当日投递额度是硬闸：用完了必须停。不然体验版用户能靠反复点开始无限投，
        // 档位差别就成了摆设。自用模式和配额<=0（后台配成不限）时直接放行。
        if (remainingDailyApply() <= 0) {
            stopRequested = true;
            if (!stoppedForQuota) {
                stoppedForQuota = true;
                appendLog(quotaExhaustedMessage());
                // 状态里带上，前端会员页/卡密条能据此提示升级
                publishQuotaNotice();
            }
            return;
        }

        // 每小时节流。预演不发消息，不用占这道闸
        if (!config.isDryRun() && !hourlyWindowOpen()) {
            stopRequested = true;
            if (!stoppedForHour) {
                stoppedForHour = true;
                appendLog(hourlyLimitMessage());
            }
            return;
        }

        // 次数卡在投递过程中用完（上报后服务端确认剩余为 0）就得停：
        // 继续发就是免费帮客户投，卖卡的那一方白亏
        if (licenseService.exhausted()) {
            stopRequested = true;
            if (!stoppedForLicense) {
                stoppedForLicense = true;
                appendLog("卡密已不能使用（次数已用完或已到期），本次任务停止");
            }
            return;
        }
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
                    if (entitlementService != null) {
                        entitlementService.recordUse(applyCounterKey());
                        entitlementService.recordUse(hourCounterKey());
                    }
                    // 次数卡扣减。异步发、失败不打断投递，见 LicenseService.reportUsage
                    licenseService.reportUsage(1);
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

    /** 今天还能投几个。测试缝没注入 entitlement 时视为不限，别把单测打崩 */
    private int remainingDailyApply() {
        return entitlementService == null ? Integer.MAX_VALUE
                : entitlementService.remainingToday(applyCounterKey(), "max_daily_apply");
    }

    /** 启动前查额度：预演模式不发消息、不计数，所以额度用完也允许进来看效果 */
    private String quotaPreCheck(PlatformConfig config) {
        if (config.isDryRun() || remainingDailyApply() > 0) {
            return null;
        }
        return quotaExhaustedMessage();
    }

    /**
     * 本平台是否必须有一句自己的话术。只有"由我们把话术打进聊天框"的平台需要
     * （Boss），猎聘点"聊一聊"时平台自己发默认招呼语、51job/智联/实习僧走投递
     * 按钮压根不发消息，空着 sayHi 也照样能投。
     */
    protected boolean greetingRequired() {
        return false;
    }

    /**
     * 启动前查话术。AI 那条路走不通（没开、没填人设、接口没配全）时，
     * 唯一能发的就是配置里的固定话术；固定话术也是空的，真投出去就是给 HR
     * 发一条空白消息，白白烧掉一个额度还容易被举报，所以直接拦住。
     */
    private String greetingPreCheck(PlatformConfig config) {
        if (config.isDryRun() || !greetingRequired() || greetingService.aiUsable()) {
            return null;
        }
        String sayHi = config.getSayHi();
        if (sayHi != null && !sayHi.isBlank()) {
            return null;
        }
        return "还没法真投：AI 话术当前不可用（没开启／没填求职者背景／接口没配全），"
                + "而这个平台的固定打招呼语是空的。请先在上方填一句打招呼语，"
                + "或到「AI 话术」页把接口配好。";
    }

    /** 额度用完的说明。带着平台名，否则用户以为整个软件都停了 */
    private String quotaExhaustedMessage() {
        int quota = entitlementService == null ? 0
                : entitlementService.current().quota("max_daily_apply");
        return "「" + displayName() + "」今日投递额度已用完（每平台 " + quota + " 个）。"
                + "明天 0 点后自动恢复，升级套餐可提高额度，也可以先切别的平台投。";
    }

    /** 额度用尽时把提示写进运行状态，前端据此引导升级而不是让用户干看 */
    private void publishQuotaNotice() {
        status.setMessage(quotaExhaustedMessage());
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
        status.setMessage(message);
        appendLog(message);
        finishState();
    }

    private void finishState() {
        status.setState(RunStatus.RunState.FINISHED);
        status.setFinishedAt(Instant.now().toString());
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
