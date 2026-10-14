package com.jobpilot.yingjiesheng;

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
 * 应届生求职网投递 API。路径与其他平台一一对应，前端一套页面换 /api/{platform} 前缀即可复用。
 *
 * POST /start 结尾的路径会被 LicenseGateFilter 拦（402），
 * 配置读取/记录查询一律放行——卡密失效时用户也要能进管理页看状态、改配置。
 */
@RestController
@RequestMapping("/api/yingjiesheng")
@RequiredArgsConstructor
public class YingjieshengController {

    private final YingjieshengService yingjieshengService;
    private final YingjieshengOptions yingjieshengOptions;
    private final LicenseService licenseService;

    /** 启动投递。卡密无效时被门禁拦截返回 402，到不了这里。 */
    @PostMapping("/start")
    public ApiResponse<RunStatus> start() {
        String error = yingjieshengService.start();
        if (error != null) {
            return ApiResponse.fail(error);
        }
        return ApiResponse.ok(yingjieshengService.status());
    }

    @PostMapping("/stop")
    public ApiResponse<RunStatus> stop() {
        yingjieshengService.stop();
        return ApiResponse.ok(yingjieshengService.status());
    }

    @GetMapping("/status")
    public ApiResponse<RunStatus> status() {
        return ApiResponse.ok(yingjieshengService.status());
    }

    @GetMapping("/config")
    public ApiResponse<YingjieshengProperties.YingjieshengConfig> config() {
        return ApiResponse.ok(yingjieshengService.configForPage());
    }

    @PutMapping("/config")
    public ApiResponse<YingjieshengProperties.YingjieshengConfig> updateConfig(
            @RequestBody YingjieshengProperties.YingjieshengConfig config) {
        yingjieshengService.saveConfig(config);
        return ApiResponse.ok(yingjieshengService.configForPage());
    }

    /** 管理页下拉框数据：只有城市（其余筛选项在列表接口里拿得到，交给打分规则） */
    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> options() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cities", yingjieshengOptions.cities());
        return ApiResponse.ok(data);
    }

    @GetMapping("/deliveries")
    public ApiResponse<List<Delivery>> deliveries(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(yingjieshengService.recentDeliveries(limit));
    }

    /** 清空投递记录：改完打分规则想整个重跑时用 */
    @DeleteMapping("/deliveries")
    public ApiResponse<Integer> clearDeliveries() {
        return ApiResponse.ok(yingjieshengService.clearDeliveries());
    }

    /** 卡密状态（管理页顶部展示，避免用户还要再开激活页） */
    @GetMapping("/license")
    public ApiResponse<Object> license() {
        return ApiResponse.ok(licenseService.status());
    }
}
