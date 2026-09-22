package com.jobpilot.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/license")
@RequiredArgsConstructor
public class LicenseController {

    private final LicenseService licenseService;
    private final EntitlementService entitlementService;
    private final LicenseProperties properties;
    private final LicenseClient client;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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

    /**
     * 当前设备的 entitlement：套餐、功能开关、每日配额。
     *
     * <p>会员页和卡密条都读这个。因为功能开关和配额都在服务端配置，
     * 后台改完这里立刻变，前端不存硬编码的权益表。
     */
    @GetMapping("/entitlement")
    public ApiResponse<Entitlement> entitlement() {
        // 先刷新再返回：激活完卡密前台会立刻来拿，不能拿到上一张卡的权益
        return ApiResponse.ok(entitlementService.refresh());
    }

    /**
     * 代理卡密服务端的公开套餐列表。
     *
     * <p>会员页是同源页面，直接调 Cloudflare 会被跨域和地址硬编码两个问题咬住；
     * 由本地服务转一手，地址仍然只有 {@code license.api-base} 一处。
     */
    @GetMapping("/plans")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> plans() {
        JsonNode node = client.get(properties.getApiBase(), "/api/plans");
        if (node == null) {
            return ApiResponse.fail("连不上卡密服务端，套餐信息暂不可用");
        }
        return ApiResponse.ok(objectMapper.convertValue(node.path("data"), Map.class));
    }

    /**
     * 解绑本机（自助退出卡密）。
     *
     * <p>两个异常都要接：{@code LicenseUnavailableException} 是连不上服务端，
     * {@code LicenseServerException} 是服务端明确拒绝——最常见的是换绑冷却期
     * （终身 3 次、每次冷却 7 天）。后者必须把服务端的原话带给用户，
     * 不然前端只显示"解绑失败"，客户完全不知道该等多久。
     */
    @PostMapping("/unbind")
    public ApiResponse<LicenseStatus> unbind() {
        try {
            return ApiResponse.ok(licenseService.unbind());
        } catch (LicenseClient.LicenseServerException | LicenseClient.LicenseUnavailableException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }
}
