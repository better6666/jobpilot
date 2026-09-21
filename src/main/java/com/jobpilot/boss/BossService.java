package com.jobpilot.boss;

import com.jobpilot.browser.BrowserManager;
import com.jobpilot.delivery.DeliveryMapper;
import com.jobpilot.delivery.DeliveryOutcome;
import com.jobpilot.delivery.DeliveryService;
import com.jobpilot.delivery.FilterResult;
import com.jobpilot.delivery.JobScorer;
import com.jobpilot.delivery.LoginResult;
import com.jobpilot.delivery.PlatformConfig;
import com.jobpilot.delivery.ProgressListener;
import com.jobpilot.delivery.RunCoordinator;
import com.jobpilot.delivery.ScoreResult;
import com.microsoft.playwright.Page;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Boss 投递编排：登录 → 逐关键词采集 → 打分过滤 → 投递 → 落库。
 *
 * 跑批骨架（异步任务、状态机、停止、去重、落库、节奏控制）全在
 * {@link DeliveryService}，这里只提供 Boss 特有的那几块：URL 构造、
 * 登录判定、拦截详情接口采集、发消息，以及 HR 活跃度过滤 + 打分。
 *
 * Boss 与其他三个平台最大的差别在投递：必须从列表页的详情面板开完整详情页，
 * 点"立即沟通"过确认框，再跳到聊天页发消息——所以它的采集和投递是交错的，
 * 列表页得一直开着停在当前卡片上（见 {@link BossDriver#processKeyword} 的注释）。
 */
@Service
public class BossService extends DeliveryService<BossJobCard> {

    private final BossProperties properties;
    private final BossDriver driver;
    private final BossOptions options;

    public BossService(BossProperties properties, BossDriver driver, DeliveryMapper mapper,
                       BrowserManager browserManager, BossOptions options, RunCoordinator coordinator) {
        super(mapper, browserManager, coordinator);
        this.properties = properties;
        this.driver = driver;
        this.options = options;
    }

    // ------------------------------------------------------------------
    // 平台特有能力
    // ------------------------------------------------------------------

    @Override
    protected String platform() {
        return "boss";
    }

    @Override
    protected String displayName() {
        return "Boss 直聘";
    }

    @Override
    protected PlatformConfig config() {
        return properties.get();
    }

    @Override
    protected String validate(PlatformConfig config) {
        String city = config.getCity();
        if (city != null && !city.isBlank()
                && BossSearchUrl.resolveCityCode(options, city) == null) {
            return "城市「" + city + "」无法识别，请填城市名（如：上海）或城市码";
        }
        return null;
    }

    @Override
    protected String cityCode(PlatformConfig config) {
        return BossSearchUrl.resolveCityCode(options, config.getCity());
    }

    @Override
    protected String searchUrl(String cityCode, String keyword, PlatformConfig config) {
        return BossSearchUrl.build(cityCode, keyword, (BossProperties.BossConfig) config);
    }

    @Override
    protected LoginResult ensureLogin(PlatformConfig config, ProgressListener listener,
                                      BooleanSupplier stop) {
        return driver.ensureLogin(config.getLoginTimeoutMinutes(), listener, stop);
    }

    @Override
    protected void collect(String keyword, String searchUrl, int maxCards,
                           ProgressListener listener, BooleanSupplier stop,
                           com.jobpilot.delivery.CardConsumer<BossJobCard> consumer) {
        driver.processKeyword(searchUrl, keyword, maxCards, listener, stop, consumer);
    }

    /**
     * HR 活跃度过滤 + 打分。两件不相干的事合成一个判定，是因为编排层只认一个
     * 返回值：被拦下的岗位要记"已过滤"和原因，放行的要带分数去投递。
     *
     * 打分器是平台无关的（{@link JobScorer}），四个平台共用；"HR 活跃度"只有
     * Boss 有这个概念，所以留在这里。
     */
    @Override
    protected FilterResult filter(BossJobCard card, PlatformConfig config) {
        BossProperties.BossConfig bossConfig = (BossProperties.BossConfig) config;
        ScoreResult scoreResult = new JobScorer(bossConfig.getScoreRules()).score(card);
        if (bossConfig.isFilterInactiveHr() && card.getBossActiveTimeDesc() != null
                && card.getBossActiveTimeDesc().contains("年")) {
            return FilterResult.reject("HR 不活跃: " + card.getBossActiveTimeDesc(), null);
        }
        if (!scoreResult.isPass()) {
            return FilterResult.reject(scoreResult.getReason(), scoreResult.getScore());
        }
        return FilterResult.pass(scoreResult.getScore());
    }

    @Override
    protected DeliveryOutcome deliver(BossJobCard card, Page listPage, PlatformConfig config,
                                      ProgressListener listener, BooleanSupplier stop) {
        BossProperties.BossConfig bossConfig = (BossProperties.BossConfig) config;
        return driver.deliver(listPage, card, bossConfig.getSayHi(), bossConfig.isDryRun(),
                listener, stop);
    }

    @Override
    protected boolean limitReachedOnPage(Page page) {
        return driver.limitReachedOnPage(page);
    }

    // ------------------------------------------------------------------
    // 管理页要的配置读写（基类只管跑批，不认配置类型）
    // ------------------------------------------------------------------

    public BossProperties.BossConfig configForPage() {
        return properties.get();
    }

    public void saveConfig(BossProperties.BossConfig config) {
        properties.save(config);
    }

    public List<String> recentLogs() {
        return status().getLogs();
    }

    /**
     * 单测接缝。
     * <p>
     * 基类的 processCard 是 protected，而它在 {@code com.jobpilot.delivery} 包里，
     * 同包的测试类访问不到继承来的 protected 成员。去重/落库这套逻辑是四个
     * 平台共用的，值得在不启动浏览器的情况下单测，所以在这里开一个包内入口。
     */
    void processCardForTest(BossJobCard card, String keyword, BossProperties.BossConfig config,
                            Page listPage) {
        processCard(card, keyword, config, listPage);
    }
}
