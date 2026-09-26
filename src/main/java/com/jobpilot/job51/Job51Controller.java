package com.jobpilot.job51;

import com.jobpilot.common.ApiResponse;
import com.jobpilot.delivery.Delivery;
import com.jobpilot.delivery.RunStatus;
import com.jobpilot.license.LicenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 51job 投递 API。路径与 Boss 版一一对应，前端一套页面换 /api/{platform} 前缀即可复用。
 *
 * POST /start 结尾的路径会被 LicenseGateFilter 拦（402），
 * 配置读取/记录查询一律放行——卡密失效时用户也要能进管理页看状态、改配置。
 */
@RestController
@RequestMapping("/api/job51")
@RequiredArgsConstructor
public class Job51Controller {

    private final Job51Service job51Service;
    private final Job51Options job51Options;
    private final LicenseService licenseService;

    /** 启动投递。卡密无效时被门禁拦截返回 402，到不了这里。 */
    @PostMapping("/start")
    public ApiResponse<RunStatus> start() {
        String error = job51Service.start();
        if (error != null) {
            return ApiResponse.fail(error);
        }
        return ApiResponse.ok(job51Service.status());
    }

    @PostMapping("/stop")
    public ApiResponse<RunStatus> stop() {
        job51Service.stop();
        return ApiResponse.ok(job51Service.status());
    }

    @GetMapping("/status")
    public ApiResponse<RunStatus> status() {
        return ApiResponse.ok(job51Service.status());
    }

    @GetMapping("/config")
    public ApiResponse<Job51Properties.Job51Config> config() {
        return ApiResponse.ok(job51Service.configForPage());
    }

    @PutMapping("/config")
    public ApiResponse<Job51Properties.Job51Config> updateConfig(
            @RequestBody Job51Properties.Job51Config config) {
        job51Service.saveConfig(config);
        return ApiResponse.ok(job51Service.configForPage());
    }

    /** 管理页下拉框数据：城市 + 薪资 + 学历 + 工作年限，键名要和 job51-options.json 一致 */
    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> options() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cities", job51Options.cities());
        data.put("salary", job51Options.filters("salary"));
        data.put("degree", job51Options.filters("degree"));
        data.put("experience", job51Options.filters("experience"));
        return ApiResponse.ok(data);
    }

    @GetMapping("/deliveries")
    public ApiResponse<List<Delivery>> deliveries(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(job51Service.recentDeliveries(limit));
    }

    /** 清空投递记录：改完打分规则想整个重跑时用 */
    @DeleteMapping("/deliveries")
    public ApiResponse<Integer> clearDeliveries() {
        return ApiResponse.ok(job51Service.clearDeliveries());
    }

    /** 卡密状态（管理页顶部展示，避免用户还要再开激活页） */
    @GetMapping("/license")
    public ApiResponse<Object> license() {
        return ApiResponse.ok(licenseService.status());
    }
}
