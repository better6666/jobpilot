package com.jobpilot.liepin;

import com.jobpilot.ai.GreetingService;
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
 * 猎聘投递编排：登录 → 逐关键词采集 → 打分过滤 → 点"聊一聊" → 落库。
 *
 * 跑批骨架全在 {@link DeliveryService}，这里只填猎聘特有的那几块。
 *
 * 与 Boss 的关键差别：猎聘的岗位实体直接来自搜索接口的 JSON（
 * {@link LiepinJobCard#parseList}），不靠 DOM 拼字段；投递动作是列表页卡片上
 * 的"聊一聊"/"继续聊"按钮，点完弹 IM 窗口，不需要像 Boss 那样先开详情页。
 * 所以猎聘的采集和投递是交错的，列表页得一直开着停在当前卡片上
 * （见 {@link LiepinDriver#processKeyword} 的注释）。
 */
@Service
public class LiepinService extends DeliveryService<LiepinJobCard> {

    private final LiepinProperties properties;
    private final LiepinDriver driver;
    private final LiepinOptions options;

    public LiepinService(LiepinProperties properties, LiepinDriver driver, DeliveryMapper mapper,
                         BrowserManager browserManager, LiepinOptions options, RunCoordinator coordinator,
                         GreetingService greetingService) {
        super(mapper, browserManager, coordinator, greetingService);
        this.properties = properties;
        this.driver = driver;
        this.options = options;
    }

    // ------------------------------------------------------------------
    // 平台特有能力
    // ------------------------------------------------------------------

    @Override
    protected String platform() {
        return "liepin";
    }

    @Override
    protected String displayName() {
        return "猎聘";
    }

    @Override
    protected PlatformConfig config() {
        return properties.get();
    }

    @Override
    protected String validate(PlatformConfig config) {
        String city = config.getCity();
        if (city != null && !city.isBlank()
                && LiepinSearchUrl.resolveCityCode(options, city) == null) {
            return "城市「" + city + "」无法识别，请填城市名（如：苏州）或猎聘城市码";
        }
        return null;
    }

    @Override
    protected String cityCode(PlatformConfig config) {
        return LiepinSearchUrl.resolveCityCode(options, config.getCity());
    }

    @Override
    protected String searchUrl(String cityCode, String keyword, PlatformConfig config) {
        return LiepinSearchUrl.build(cityCode, keyword, (LiepinProperties.LiepinConfig) config);
    }

    @Override
    protected LoginResult ensureLogin(PlatformConfig config, ProgressListener listener,
                                      BooleanSupplier stop) {
        return driver.ensureLogin(config.getLoginTimeoutMinutes(), listener, stop);
    }

    @Override
    protected void collect(String keyword, String searchUrl, int maxCards,
                           ProgressListener listener, BooleanSupplier stop,
                           CardConsumer<LiepinJobCard> consumer) {
        driver.processKeyword(searchUrl, keyword, maxCards, listener, stop, consumer);
    }

    /**
     * 打分。猎聘只有 URL 上的城市/薪资两个筛选项，剩下的全交给规则：
     * 学历、经验、行业、JD 都能从接口实体里取到，JD 取不到时 jdRules 自动不命中。
     */
    @Override
    protected FilterResult filter(LiepinJobCard card, PlatformConfig config) {
        ScoreResult result = new JobScorer(config.getScoreRules()).score(card);
        if (!result.isPass()) {
            return FilterResult.reject(result.getReason(), result.getScore());
        }
        return FilterResult.pass(result.getScore());
    }

    /**
     * 猎聘的招呼分两句：平台点击时自动发的那句是它自己的模板，管不了；
     * 我们能控的是聊天窗里补的追问，所以这里生成的是追问句。
     */
    @Override
    protected DeliveryOutcome deliver(LiepinJobCard card, Page listPage, PlatformConfig config,
                                      ProgressListener listener, BooleanSupplier stop) {
        LiepinProperties.LiepinConfig liepinConfig = (LiepinProperties.LiepinConfig) config;
        GreetingService.Greeting greeting = followUpFor(card, config);
        if (greeting.note() != null) {
            appendLog("话术兜底 | " + brief(card) + " | " + greeting.note());
        }
        return driver.deliver(card, listPage, liepinConfig, greeting.text(), listener, stop);
    }

    // ------------------------------------------------------------------
    // 管理页要的配置读写（基类只管跑批，不认配置类型）
    // ------------------------------------------------------------------

    public LiepinProperties.LiepinConfig configForPage() {
        return properties.get();
    }

    public void saveConfig(LiepinProperties.LiepinConfig config) {
        properties.save(config);
    }

    public List<String> recentLogs() {
        return status().getLogs();
    }

    /**
     * 单测接缝，理由同 {@link com.jobpilot.boss.BossService#processCardForTest}。
     */
    void processCardForTest(LiepinJobCard card, String keyword, LiepinProperties.LiepinConfig config,
                            Page listPage) {
        processCard(card, keyword, config, listPage);
    }
}
