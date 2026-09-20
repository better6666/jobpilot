package com.jobpilot.boss;

import com.jobpilot.common.ApiResponse;
import com.jobpilot.license.LicenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boss 投递 API。
 *
 * POST /start 结尾的路径会被 LicenseGateFilter 拦（402），
 * 配置读取/记录查询一律放行——卡密失效时用户也要能进管理页看状态、改配置。
 */
@RestController
@RequestMapping("/api/boss")
@RequiredArgsConstructor
public class BossController {

    private final BossService bossService;
    private final BossProperties bossProperties;
    private final BossOptions bossOptions;
    private final LicenseService licenseService;

    /** 启动投递。卡密无效时被门禁拦截返回 402，到不了这里。 */
    @PostMapping("/start")
    public ApiResponse<BossService.RunStatus> start() {
        String error = bossService.start();
        if (error != null) {
            return ApiResponse.fail(error);
        }
        return ApiResponse.ok(bossService.status());
    }

    @PostMapping("/stop")
    public ApiResponse<BossService.RunStatus> stop() {
        bossService.stop();
        return ApiResponse.ok(bossService.status());
    }

    @GetMapping("/status")
    public ApiResponse<BossService.RunStatus> status() {
        return ApiResponse.ok(bossService.status());
    }

    @GetMapping("/config")
    public ApiResponse<BossProperties.BossConfig> config() {
        return ApiResponse.ok(bossProperties.get());
    }

    @PutMapping("/config")
    public ApiResponse<BossProperties.BossConfig> updateConfig(@RequestBody BossProperties.BossConfig config) {
        bossProperties.save(config);
        return ApiResponse.ok(bossProperties.get());
    }

    /** 管理页下拉框数据：城市 + 各筛选项码表 */
    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> options() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cities", bossOptions.cities());
        data.put("salary", bossOptions.filters("salary"));
        data.put("experience", bossOptions.filters("experience"));
        data.put("degree", bossOptions.filters("degree"));
        data.put("scale", bossOptions.filters("scale"));
        data.put("stage", bossOptions.filters("stage"));
        data.put("jobType", bossOptions.filters("jobType"));
        return ApiResponse.ok(data);
    }

    @GetMapping("/deliveries")
    public ApiResponse<List<BossDelivery>> deliveries(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(bossService.recentDeliveries(limit));
    }

    /** 清空投递记录：改完打分规则想整个重跑时用 */
    @DeleteMapping("/deliveries")
    public ApiResponse<Integer> clearDeliveries() {
        return ApiResponse.ok(bossService.clearDeliveries());
    }

    /** 卡密状态（管理页顶部展示，避免用户还要再开激活页） */
    @GetMapping("/license")
    public ApiResponse<Object> license() {
        return ApiResponse.ok(licenseService.status());
    }
}
