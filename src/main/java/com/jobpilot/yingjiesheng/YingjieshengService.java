package com.jobpilot.yingjiesheng;

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
 * 应届生求职网投递编排：登录 → 逐关键词采集（拦搜索接口）→ 本地筛 → 打分 → 列表页「立即申请」→ 落库。
 *
 * <p>列表接口给的字段比 51job 还全（学历、经验、规模、HR、HR 活跃度都有），
 * 只缺 JD——{@code jdRules} 在这个平台上不会命中，配规则时要知道。
 */
@Service
public class YingjieshengService extends DeliveryService<YingjieshengJobCard> {

    private final YingjieshengProperties properties;
    private final YingjieshengDriver driver;

    public YingjieshengService(YingjieshengProperties properties, YingjieshengDriver driver, DeliveryMapper mapper,
                               BrowserManager browserManager, RunCoordinator coordinator,
                               GreetingService greetingService, LicenseService licenseService,
                               com.jobpilot.license.EntitlementService entitlementService) {
        super(mapper, browserManager, coordinator, greetingService, licenseService, entitlementService);
        this.properties = properties;
        this.driver = driver;
    }

    @Override
    protected String platform() {
        return "yingjiesheng";
    }

    @Override
    protected String displayName() {
        return "应届生求职网";
    }

    @Override
    protected PlatformConfig config() {
        return properties.get();
    }

    @Override
    protected String validate(PlatformConfig config) {
        return null;
    }

    @Override
    protected String cityCode(PlatformConfig config) {
        return config.getCity();
    }

    @Override
    protected String searchUrl(String cityCode, String keyword, PlatformConfig config) {
        return YingjieshengDriver.searchUrl(keyword);
    }

    @Override
    protected LoginResult ensureLogin(PlatformConfig config, ProgressListener listener, BooleanSupplier stop) {
        return driver.ensureLogin(config.getLoginTimeoutMinutes(), listener, stop);
    }

    @Override
    protected void collect(String keyword, String searchUrl, int maxCards, ProgressListener listener,
                           BooleanSupplier stop, CardConsumer<YingjieshengJobCard> consumer) {
        // 城市要靠点筛选栏，基类的 collect 不带配置，这里现读一次
        driver.processKeyword(searchUrl, keyword, properties.get().getCity(), maxCards, listener, stop, consumer);
    }

    @Override
    protected FilterResult filter(YingjieshengJobCard card, PlatformConfig config) {
        String local = localReject(card, config);
        if (local != null) {
            return FilterResult.rejectNoScore(local);
        }
        ScoreResult result = new JobScorer(config.getScoreRules()).score(card);
        if (!result.isPass()) {
            return FilterResult.reject(result.getReason(), result.getScore());
        }
        return FilterResult.pass(result.getScore());
    }

    /** 转载岗位投不了；筛选栏没点上城市时，靠这里兜住异地岗位 */
    static String localReject(YingjieshengJobCard card, PlatformConfig config) {
        if (card.isReprint()) {
            return "转载岗位，要去原网站投";
        }
        String city = config.getCity() == null ? "" : config.getCity().trim();
        if (!city.isEmpty() && !card.isCityMatchedByPlatform()
                && card.getCityName() != null && !card.getCityName().contains(city)) {
            return "城市不符（" + card.getCityName() + "）";
        }
        return null;
    }

    @Override
    protected DeliveryOutcome deliver(YingjieshengJobCard card, Page listPage, PlatformConfig config,
                                      ProgressListener listener, BooleanSupplier stop) {
        return driver.deliver(card, listPage, (YingjieshengProperties.YingjieshengConfig) config, listener, stop);
    }

    public YingjieshengProperties.YingjieshengConfig configForPage() {
        return properties.get();
    }

    public void saveConfig(YingjieshengProperties.YingjieshengConfig config) {
        properties.save(config);
    }

    public List<String> recentLogs() {
        return status().getLogs();
    }

    /** 单测接缝，理由同 {@link com.jobpilot.zhilian.ZhilianService#processCardForTest} */
    void processCardForTest(YingjieshengJobCard card, String keyword,
                            YingjieshengProperties.YingjieshengConfig config, Page listPage) {
        processCard(card, keyword, config, listPage);
    }
}
