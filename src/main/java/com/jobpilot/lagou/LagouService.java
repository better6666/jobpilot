package com.jobpilot.lagou;

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
 * 拉勾投递编排：登录 → 逐页读 __NEXT_DATA__ → 打分（JD 也有）→ 详情页"投简历" → 落库。
 *
 * <p>城市直接传中文名；学历大专时在搜索里加 xl（见 {@link LagouSearchUrl}），其余交给本地过滤。
 */
@Service
public class LagouService extends DeliveryService<LagouJobCard> {

    private final LagouProperties properties;
    private final LagouDriver driver;
    private final LagouOptions options;

    public LagouService(LagouProperties properties, LagouDriver driver, LagouOptions options, DeliveryMapper mapper,
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
        return "lagou";
    }

    @Override
    protected String displayName() {
        return "拉勾";
    }

    @Override
    protected PlatformConfig config() {
        return properties.get();
    }

    @Override
    protected String validate(PlatformConfig config) {
        return null;
    }

    /** 拉勾的 city 参数直接吃中文名 */
    @Override
    protected String cityCode(PlatformConfig config) {
        String city = config.getCity();
        return city == null || city.isBlank() ? null : city.trim();
    }

    @Override
    protected String searchUrl(String cityCode, String keyword, PlatformConfig config) {
        return LagouSearchUrl.build(cityCode, keyword, config.getMyDegree(), 1);
    }

    @Override
    protected LoginResult ensureLogin(PlatformConfig config, ProgressListener listener, BooleanSupplier stop) {
        return driver.ensureLogin(config.getLoginTimeoutMinutes(), listener, stop);
    }

    /** 翻页要自己拼 pn，基类给的 searchUrl 只用来记日志 */
    @Override
    protected void collect(String keyword, String searchUrl, int maxCards, ProgressListener listener,
                           BooleanSupplier stop, CardConsumer<LagouJobCard> consumer) {
        LagouProperties.LagouConfig c = properties.get();
        driver.processKeyword(cityCode(c), keyword, c.getMyDegree(), maxCards, listener, stop, consumer);
    }

    @Override
    protected FilterResult filter(LagouJobCard card, PlatformConfig config) {
        ScoreResult result = new JobScorer(config.getScoreRules()).score(card);
        if (!result.isPass()) {
            return FilterResult.reject(result.getReason(), result.getScore());
        }
        return FilterResult.pass(result.getScore());
    }

    @Override
    protected DeliveryOutcome deliver(LagouJobCard card, Page listPage, PlatformConfig config,
                                      ProgressListener listener, BooleanSupplier stop) {
        return driver.deliver(card, (LagouProperties.LagouConfig) config, listener, stop);
    }

    public LagouProperties.LagouConfig configForPage() {
        return properties.get();
    }

    public void saveConfig(LagouProperties.LagouConfig config) {
        properties.save(config);
    }

    public List<String> recentLogs() {
        return status().getLogs();
    }

    void processCardForTest(LagouJobCard card, String keyword, LagouProperties.LagouConfig config, Page listPage) {
        processCard(card, keyword, config, listPage);
    }
}
