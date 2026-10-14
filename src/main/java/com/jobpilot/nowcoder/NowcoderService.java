package com.jobpilot.nowcoder;

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
 * 牛客投递编排：登录 → 逐关键词采集 → 本地筛城市/类型 → 打分 → 详情页「立即申请」→ 落库。
 *
 * <p>和别的平台的差别：牛客以互联网校招、实习为主，非技术方向（陈列、门店）
 * 岗位很少；城市和招聘类型筛不进搜索地址，全在 {@link #filter} 里本地判。
 */
@Service
public class NowcoderService extends DeliveryService<NowcoderJobCard> {

    private final NowcoderProperties properties;
    private final NowcoderDriver driver;

    public NowcoderService(NowcoderProperties properties, NowcoderDriver driver, DeliveryMapper mapper,
                           BrowserManager browserManager, RunCoordinator coordinator,
                           GreetingService greetingService, LicenseService licenseService,
                           com.jobpilot.license.EntitlementService entitlementService) {
        super(mapper, browserManager, coordinator, greetingService, licenseService, entitlementService);
        this.properties = properties;
        this.driver = driver;
    }

    @Override
    protected String platform() {
        return "nowcoder";
    }

    @Override
    protected String displayName() {
        return "牛客";
    }

    @Override
    protected PlatformConfig config() {
        return properties.get();
    }

    @Override
    protected String validate(PlatformConfig config) {
        return null;
    }

    /** 城市不进 URL，这里原样传下去只为了日志好看 */
    @Override
    protected String cityCode(PlatformConfig config) {
        return config.getCity();
    }

    @Override
    protected String searchUrl(String cityCode, String keyword, PlatformConfig config) {
        return NowcoderDriver.searchUrl(keyword);
    }

    @Override
    protected LoginResult ensureLogin(PlatformConfig config, ProgressListener listener, BooleanSupplier stop) {
        return driver.ensureLogin(config.getLoginTimeoutMinutes(), listener, stop);
    }

    @Override
    protected void collect(String keyword, String searchUrl, int maxCards, ProgressListener listener,
                           BooleanSupplier stop, CardConsumer<NowcoderJobCard> consumer) {
        driver.processKeyword(searchUrl, keyword, maxCards, listener, stop, consumer);
    }

    @Override
    protected FilterResult filter(NowcoderJobCard card, PlatformConfig config) {
        String local = localReject(card, (NowcoderProperties.NowcoderConfig) config);
        if (local != null) {
            return FilterResult.rejectNoScore(local);
        }
        ScoreResult result = new JobScorer(config.getScoreRules()).score(card);
        if (!result.isPass()) {
            return FilterResult.reject(result.getReason(), result.getScore());
        }
        return FilterResult.pass(result.getScore());
    }

    /** 搜索地址筛不了的三样：官网直投、城市、招聘类型 */
    static String localReject(NowcoderJobCard card, NowcoderProperties.NowcoderConfig config) {
        if (card.isExternal()) {
            return "官网直投岗位，要去企业官网自己填表";
        }
        String city = config.getCity() == null ? "" : config.getCity().trim();
        // 卡片没读到城市时放行：宁可多投一个也别因为页面改版全拦掉
        if (!city.isEmpty() && card.getCityName() != null && !card.getCityName().contains(city)) {
            return "城市不符（" + card.getCityName() + "）";
        }
        String type = config.getRecruitType() == null ? "" : config.getRecruitType().trim();
        if (!type.isEmpty() && card.getRecruitType() != null && !card.getRecruitType().equals(type)) {
            return "招聘类型不符（" + card.getRecruitType() + "）";
        }
        return null;
    }

    @Override
    protected DeliveryOutcome deliver(NowcoderJobCard card, Page listPage, PlatformConfig config,
                                      ProgressListener listener, BooleanSupplier stop) {
        return driver.deliver(card, (NowcoderProperties.NowcoderConfig) config, listener, stop);
    }

    public NowcoderProperties.NowcoderConfig configForPage() {
        return properties.get();
    }

    public void saveConfig(NowcoderProperties.NowcoderConfig config) {
        properties.save(config);
    }

    public List<String> recentLogs() {
        return status().getLogs();
    }

    /** 单测接缝，理由同 {@link com.jobpilot.zhilian.ZhilianService#processCardForTest} */
    void processCardForTest(NowcoderJobCard card, String keyword,
                            NowcoderProperties.NowcoderConfig config, Page listPage) {
        processCard(card, keyword, config, listPage);
    }
}
