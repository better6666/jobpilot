package com.jobpilot.wuba;

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
import com.jobpilot.license.LicenseService;
import com.microsoft.playwright.Page;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 58 同城投递编排：登录 → 逐关键词采集（城市是二级域名）→ 打分 → 列表页"申请" → 落库。
 *
 * <p>学历、经验在列表卡片上就有，"我的条件"本地过滤直接生效；网址里只能筛薪资。
 */
@Service
public class WubaService extends DeliveryService<WubaJobCard> {

    private final WubaProperties properties;
    private final WubaDriver driver;
    private final WubaOptions options;

    public WubaService(WubaProperties properties, WubaDriver driver, WubaOptions options, DeliveryMapper mapper,
                       BrowserManager browserManager, RunCoordinator coordinator,
                       GreetingService greetingService, LicenseService licenseService,
                       com.jobpilot.license.EntitlementService entitlementService) {
        super(mapper, browserManager, coordinator, greetingService, licenseService, entitlementService);
        this.properties = properties;
        this.driver = driver;
        this.options = options;
    }

    @Override
    protected String platform() {
        return "wuba";
    }

    @Override
    protected String displayName() {
        return "58同城";
    }

    @Override
    protected PlatformConfig config() {
        return properties.get();
    }

    @Override
    protected String validate(PlatformConfig config) {
        String city = config.getCity();
        if (city != null && !city.isBlank() && cityCode(config) == null) {
            return "城市「" + city.trim() + "」不在 58 同城码表里，请从下拉框里选";
        }
        return null;
    }

    /** 城市名 → 二级域名；直接填了 su 这种缩写也认 */
    @Override
    protected String cityCode(PlatformConfig config) {
        String city = config.getCity();
        if (city == null || city.isBlank()) {
            return null;
        }
        String c = city.trim();
        if (c.matches("[a-z]{2,10}")) {
            return c;
        }
        return options.cityCode(c);
    }

    @Override
    protected String searchUrl(String cityCode, String keyword, PlatformConfig config) {
        return WubaSearchUrl.build(cityCode, keyword, config.getSalary());
    }

    @Override
    protected LoginResult ensureLogin(PlatformConfig config, ProgressListener listener, BooleanSupplier stop) {
        return driver.ensureLogin(cityCode(config), config.getLoginTimeoutMinutes(), listener, stop);
    }

    @Override
    protected void collect(String keyword, String searchUrl, int maxCards, ProgressListener listener,
                           BooleanSupplier stop, CardConsumer<WubaJobCard> consumer) {
        String city = properties.get().getCity();
        driver.processKeyword(searchUrl, keyword, city == null ? null : city.trim(), maxCards, listener, stop, consumer);
    }

    @Override
    protected FilterResult filter(WubaJobCard card, PlatformConfig config) {
        ScoreResult result = new JobScorer(config.getScoreRules()).score(card);
        if (!result.isPass()) {
            return FilterResult.reject(result.getReason(), result.getScore());
        }
        return FilterResult.pass(result.getScore());
    }

    @Override
    protected DeliveryOutcome deliver(WubaJobCard card, Page listPage, PlatformConfig config,
                                      ProgressListener listener, BooleanSupplier stop) {
        return driver.deliver(card, listPage, (WubaProperties.WubaConfig) config, listener, stop);
    }

    public WubaProperties.WubaConfig configForPage() {
        return properties.get();
    }

    public void saveConfig(WubaProperties.WubaConfig config) {
        properties.save(config);
    }

    public List<String> recentLogs() {
        return status().getLogs();
    }

    void processCardForTest(WubaJobCard card, String keyword, WubaProperties.WubaConfig config, Page listPage) {
        processCard(card, keyword, config, listPage);
    }
}
