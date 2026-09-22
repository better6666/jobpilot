package com.jobpilot.shixiseng;

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
import com.jobpilot.license.LicenseService;
import com.jobpilot.delivery.ScoreResult;
import com.microsoft.playwright.Page;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 实习僧投递编排：登录 → 逐关键词采集 → 打分过滤 → 点"投个简历" → 落库。
 *
 * 跑批骨架全在 {@link DeliveryService}，这里只填实习僧特有的那几块。
 *
 * 与另四个平台的关键差别：
 * <ul>
 *   <li><b>只有实习/校招岗</b>，没有薪资档位、经验、学历这些筛选，码表里只有城市一组</li>
 *   <li><b>列表页字段最少</b>：只有岗位名/日薪/城市/每周几天/几个月/公司/行业。
 *       经验、学历、HR、规模在列表页一律采不到，只有 JD 在详情页——所以
 *       {@code jdRules} 是唯一能用的深度规则组，用户配规则时要知道</li>
 *   <li><b>岗位名混着 icon-font 私用区字符</b>，解析时必须剥掉，否则打分全打飞</li>
 * </ul>
 */
@Service
public class ShixisengService extends DeliveryService<ShixisengJobCard> {

    private final ShixisengProperties properties;
    private final ShixisengDriver driver;
    private final ShixisengOptions options;

    public ShixisengService(ShixisengProperties properties, ShixisengDriver driver, DeliveryMapper mapper,
                            BrowserManager browserManager, ShixisengOptions options, RunCoordinator coordinator,
                            GreetingService greetingService, LicenseService licenseService,
                          com.jobpilot.license.EntitlementService entitlementService) {
        super(mapper, browserManager, coordinator, greetingService, licenseService,
                entitlementService);
        this.properties = properties;
        this.driver = driver;
        this.options = options;
    }

    // ------------------------------------------------------------------
    // 平台特有能力
    // ------------------------------------------------------------------

    @Override
    protected String platform() {
        return "shixiseng";
    }

    @Override
    protected String displayName() {
        return "实习僧";
    }

    @Override
    protected PlatformConfig config() {
        return properties.get();
    }

    @Override
    protected String validate(PlatformConfig config) {
        // 城市码表查不到也放行：码表的 code 就是编码后的城市名，
        // 驱动层对未录入的城市按名字现编码，不会把用户卡死
        return null;
    }

    @Override
    protected String cityCode(PlatformConfig config) {
        String city = config.getCity();
        if (city == null || city.isBlank()) {
            return null;
        }
        String code = options.cityCode(city.trim());
        // 码表里没有这座城市：按名字现编码，和码表里的 code 同一种形式
        return code != null ? code : java.net.URLEncoder.encode(city.trim(),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    protected String searchUrl(String cityCode, String keyword, PlatformConfig config) {
        return ShixisengSearchUrl.build(cityCode, keyword, 1);
    }

    @Override
    protected LoginResult ensureLogin(PlatformConfig config, ProgressListener listener,
                                      BooleanSupplier stop) {
        return driver.ensureLogin(config.getLoginTimeoutMinutes(), listener, stop);
    }

    @Override
    protected void collect(String keyword, String searchUrl, int maxCards,
                           ProgressListener listener, BooleanSupplier stop,
                           CardConsumer<ShixisengJobCard> consumer) {
        driver.processKeyword(searchUrl, keyword, maxCards, listener, stop, consumer);
    }

    /**
     * 打分。实习僧列表只采得到职位名和行业，所以实际能用的规则组是
     * jobRules / industryRules / titleReject；degreeRules / experienceRules
     * 会因为字段为 null 自动不命中。JD 要等详情页拉回来，由驱动层回填后
     * 同一套规则会在投递前再算一次（见 {@link ShixisengDriver}）。
     */
    @Override
    protected FilterResult filter(ShixisengJobCard card, PlatformConfig config) {
        ScoreResult result = new JobScorer(config.getScoreRules()).score(card);
        if (!result.isPass()) {
            return FilterResult.reject(result.getReason(), result.getScore());
        }
        return FilterResult.pass(result.getScore());
    }

    @Override
    protected DeliveryOutcome deliver(ShixisengJobCard card, Page listPage, PlatformConfig config,
                                      ProgressListener listener, BooleanSupplier stop) {
        return driver.deliver(card, listPage, (ShixisengProperties.ShixisengConfig) config, listener, stop);
    }

    // ------------------------------------------------------------------
    // 管理页要的配置读写（基类只管跑批，不认配置类型）
    // ------------------------------------------------------------------

    public ShixisengProperties.ShixisengConfig configForPage() {
        return properties.get();
    }

    public void saveConfig(ShixisengProperties.ShixisengConfig config) {
        properties.save(config);
    }

    public List<String> recentLogs() {
        return status().getLogs();
    }

    /**
     * 单测接缝，理由同 {@link com.jobpilot.zhilian.ZhilianService#processCardForTest}。
     */
    void processCardForTest(ShixisengJobCard card, String keyword,
                            ShixisengProperties.ShixisengConfig config, Page listPage) {
        processCard(card, keyword, config, listPage);
    }
}
