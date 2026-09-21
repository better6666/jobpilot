package com.jobpilot.zhilian;

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
 * 智联投递编排：登录 → 逐关键词采集 → 打分过滤 → 点卡片+立即投递 → 落库。
 *
 * 跑批骨架全在 {@link DeliveryService}，这里只填智联特有的那几块。
 *
 * 与另外三个平台的关键差别：智联<b>没有任何可拦的接口</b>，岗位字段全部
 * 从 DOM 读，所以学历/HR/行业/规模/JD 都是 null——打分规则里这几组会
 * 自然不命中，用户配规则时要知道这件事。投递也要点两下（卡片 → 立即投递
 * → 投递简历），比 Boss/猎聘/51job 都多一步。
 */
@Service
public class ZhilianService extends DeliveryService<ZhilianJobCard> {

    private final ZhilianProperties properties;
    private final ZhilianDriver driver;
    private final ZhilianOptions options;

    public ZhilianService(ZhilianProperties properties, ZhilianDriver driver, DeliveryMapper mapper,
                          BrowserManager browserManager, ZhilianOptions options, RunCoordinator coordinator,
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
        return "zhilian";
    }

    @Override
    protected String displayName() {
        return "智联招聘";
    }

    @Override
    protected PlatformConfig config() {
        return properties.get();
    }

    @Override
    protected String validate(PlatformConfig config) {
        String city = config.getCity();
        if (city != null && !city.isBlank()
                && ZhilianSearchUrl.resolveCityCode(options, city) == null) {
            return "城市「" + city + "」无法识别，请填城市名（如：苏州）或智联城市码";
        }
        return null;
    }

    @Override
    protected String cityCode(PlatformConfig config) {
        return ZhilianSearchUrl.resolveCityCode(options, config.getCity());
    }

    /**
     * 构造搜索地址（第 1 页）。
     * <p>
     * keyword 参数在这里用不上：智联的关键词不走 URL，由
     * {@link ZhilianDriver#processKeyword} 导航后往输入框填。保留参数是为了和
     * 另外三个平台的签名一致。
     */
    @Override
    protected String searchUrl(String cityCode, String keyword, PlatformConfig config) {
        return ZhilianSearchUrl.build(cityCode, keyword, (ZhilianProperties.ZhilianConfig) config, 1);
    }

    @Override
    protected LoginResult ensureLogin(PlatformConfig config, ProgressListener listener,
                                      BooleanSupplier stop) {
        return driver.ensureLogin(config.getLoginTimeoutMinutes(), listener, stop);
    }

    @Override
    protected void collect(String keyword, String searchUrl, int maxCards,
                           ProgressListener listener, BooleanSupplier stop,
                           CardConsumer<ZhilianJobCard> consumer) {
        driver.processKeyword(searchUrl, keyword, maxCards, listener, stop, consumer);
    }

    /**
     * 打分。智联只采得到职位名、经验、薪资三组，所以实际能用的规则组是
     * jobRules / experienceRules / titleReject；degreeRules / industryRules /
     * jdRules 会因为字段为 null 而自动不命中。
     */
    @Override
    protected FilterResult filter(ZhilianJobCard card, PlatformConfig config) {
        ScoreResult result = new JobScorer(config.getScoreRules()).score(card);
        if (!result.isPass()) {
            return FilterResult.reject(result.getReason(), result.getScore());
        }
        return FilterResult.pass(result.getScore());
    }

    @Override
    protected DeliveryOutcome deliver(ZhilianJobCard card, Page listPage, PlatformConfig config,
                                      ProgressListener listener, BooleanSupplier stop) {
        ZhilianProperties.ZhilianConfig zhilianConfig = (ZhilianProperties.ZhilianConfig) config;
        return driver.deliver(card, listPage, zhilianConfig, listener, stop);
    }

    // ------------------------------------------------------------------
    // 管理页要的配置读写（基类只管跑批，不认配置类型）
    // ------------------------------------------------------------------

    public ZhilianProperties.ZhilianConfig configForPage() {
        return properties.get();
    }

    public void saveConfig(ZhilianProperties.ZhilianConfig config) {
        properties.save(config);
    }

    public List<String> recentLogs() {
        return status().getLogs();
    }

    /**
     * 单测接缝，理由同 {@link com.jobpilot.boss.BossService#processCardForTest}。
     */
    void processCardForTest(ZhilianJobCard card, String keyword, ZhilianProperties.ZhilianConfig config,
                            Page listPage) {
        processCard(card, keyword, config, listPage);
    }
}
