package com.jobpilot.license;

import com.jobpilot.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/license")
@RequiredArgsConstructor
public class LicenseController {

    private final LicenseService licenseService;

    @GetMapping("/status")
    public ApiResponse<LicenseStatus> status() {
        return ApiResponse.ok(licenseService.status());
    }

    @PostMapping("/activate")
    public ApiResponse<LicenseStatus> activate(@RequestBody(required = false) Map<String, String> body) {
        String cardKey = body == null ? null : body.get("cardKey");
        if (cardKey == null || cardKey.isBlank()) {
            return ApiResponse.fail("卡密不能为空");
        }
        try {
            return ApiResponse.ok(licenseService.activate(cardKey));
        } catch (LicenseClient.LicenseServerException | LicenseClient.LicenseUnavailableException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/unbind")
    public ApiResponse<LicenseStatus> unbind() {
        try {
            return ApiResponse.ok(licenseService.unbind());
        } catch (LicenseClient.LicenseUnavailableException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }
}
