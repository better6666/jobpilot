package com.jobpilot.system;

import com.jobpilot.common.ApiResponse;
import com.jobpilot.license.LicenseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * 健康检查 + 软件入口。
 *
 * <p>根路径就是"打开软件看到的第一个页面"。没激活/已到期/已作废时先跳激活页，
 * 卡密填对了才进投递界面——客户拿到软件第一眼看到的是激活码输入框，
 * 不会一头扎进投递页然后发现 `/start` 全是 402。
 */
@RestController
public class HealthController {

    private final LicenseService licenseService;

    public HealthController(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @GetMapping("/api/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of("status", "UP", "time", Instant.now().toString()));
    }

    /** 根路径：没卡先激活，有卡直接进投递管理页 */
    @GetMapping("/")
    public void index(HttpServletResponse response) throws IOException {
        response.sendRedirect(licenseService.needsActivation() ? "/license.html" : "/boss.html");
    }
}
