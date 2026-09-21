package com.jobpilot.job51;

import com.jobpilot.browser.BrowserManager;
import com.jobpilot.delivery.CardConsumer;
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
 * 51job 投递编排：登录 → 逐关键词采集 → 打分过滤 → 点"投递" → 落库。
 *
 * 跑批骨架全在 {@link DeliveryService}，这里只填 51job 特有的那几块。
 *
 * 与 Boss/猎聘的关键差别：投递按钮就在列表页卡片上，不用先开详情页，
 * 所以一次点击就完成投递，没有"确认框 → 跳聊天页 → 发消息"那条链。
 * 代价是列表页必须一直开着不能刷新——刷新会丢掉已翻到的页码，而且
 * 51job 的搜索接口要重新排序交互才会触发。
 */
@Service
public class Job51Service extends DeliveryService<Job51JobCard> {

    private final Job51Properties properties;
    private final Job51Driver driver;
    private final Job51Options options;

    public Job51Service(Job51Properties properties, Job51Driver driver, DeliveryMapper mapper,
                        BrowserManager browserManager, Job51Options options, RunCoordinator coordinator) {
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
        return "job51";
    }

    @Override
    protected String displayName() {
        return "51job";
    }

    @Override
    protected PlatformConfig config() {
        return properties.get();
    }

    @Override
    protected String validate(PlatformConfig config) {
        String city = config.getCity();
        if (city != null && !city.isBlank()
                && Job51SearchUrl.resolveCityCode(options, city) == null) {
            return "城市「" + city + "」无法识别，请填城市名（如：苏州）或 51job 地区码";
        }
        return null;
    }

    @Override
    protected String cityCode(PlatformConfig config) {
        return Job51SearchUrl.resolveCityCode(options, config.getCity());
    }

    @Override
    protected String searchUrl(String cityCode, String keyword, PlatformConfig config) {
        return Job51SearchUrl.build(cityCode, keyword, (Job51Properties.Job51Config) config);
    }

    @Override
    protected LoginResult ensureLogin(PlatformConfig config, ProgressListener listener,
                                      BooleanSupplier stop) {
        return driver.ensureLogin(config.getLoginTimeoutMinutes(), listener, stop);
    }

    @Override
    protected void collect(String keyword, String searchUrl, int maxCards,
                           ProgressListener listener, BooleanSupplier stop,
                           CardConsumer<Job51JobCard> consumer) {
        driver.processKeyword(searchUrl, keyword, maxCards, listener, stop, consumer);
    }

    /**
     * 打分。51job 的学历/经验字段在接口里就有（degreeString / workYearString），
     * JD 只有部分接口版本返回，取不到时 jdRules 自动不命中。
     */
    @Override
    protected FilterResult filter(Job51JobCard card, PlatformConfig config) {
        ScoreResult result = new JobScorer(config.getScoreRules()).score(card);
        if (!result.isPass()) {
            return FilterResult.reject(result.getReason(), result.getScore());
        }
        return FilterResult.pass(result.getScore());
    }

    @Override
    protected DeliveryOutcome deliver(Job51JobCard card, Page listPage, PlatformConfig config,
                                      ProgressListener listener, BooleanSupplier stop) {
        Job51Properties.Job51Config job51Config = (Job51Properties.Job51Config) config;
        return driver.deliver(card, listPage, job51Config, listener, stop);
    }

    // ------------------------------------------------------------------
    // 管理页要的配置读写（基类只管跑批，不认配置类型）
    // ------------------------------------------------------------------

    public Job51Properties.Job51Config configForPage() {
        return properties.get();
    }

    public void saveConfig(Job51Properties.Job51Config config) {
        properties.save(config);
    }

    public List<String> recentLogs() {
        return status().getLogs();
    }

    /**
     * 单测接缝，理由同 {@link com.jobpilot.boss.BossService#processCardForTest}。
     */
    void processCardForTest(Job51JobCard card, String keyword, Job51Properties.Job51Config config,
                            Page listPage) {
        processCard(card, keyword, config, listPage);
    }
}
