package com.jobpilot.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 卡密服务端 HTTP 客户端（JDK 自带 HttpClient，不引第三方依赖）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LicenseClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper;

    /** 服务端明确拒绝（卡不存在 / 已到期 / 已作废等业务错误） */
    public static class LicenseServerException extends RuntimeException {
        private final int status;
        private final String code;

        public LicenseServerException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int getStatus() {
            return status;
        }

        public String getCode() {
            return code;
        }
    }

    /** 网络不可达：连不上、超时、响应体无法解析 */
    public static class LicenseUnavailableException extends RuntimeException {
        public LicenseUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * GET 一个只读端点。和 {@link #post} 一样，网络不可达返回 null——
     * 调用方要能区分"服务端说没有"和"服务端没答上"，前者照常显示，
     *后者按不可用处理。
     */
    public JsonNode get(String apiBase, String path) {
        String base = apiBase == null ? "" : apiBase.trim();
        if (base.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(base.replaceAll("/+$", "") + path);
        } catch (IllegalArgumentException e) {
            log.warn("license.api-base 不是合法地址: {}", base);
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            log.warn("license.api-base 不是合法的 http(s) 地址: {}", base);
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            String bodyText = response.body();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("卡密服务端 GET {} 返回 {}", path, response.statusCode());
                return null;
            }
            return (bodyText == null || bodyText.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(bodyText);
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("卡密服务端 GET 失败 | {} | {}", path, e.toString());
            return null;
        }
    }

    /**
     * @return 响应体 JSON；网络不可达（未配置地址 / 地址非法 / 连不上）返回 null，
     *         由调用方按宽限逻辑处理
     * @throws LicenseServerException 服务端返回了明确的业务错误
     */
    public JsonNode post(String apiBase, String path, Map<String, Object> body) {
        String base = apiBase == null ? "" : apiBase.trim();
        if (base.isBlank()) {
            log.warn("未配置 license.api-base，{} 请求跳过（按服务端不可达处理）", path);
            return null;
        }
        URI uri;
        try {
            uri = URI.create(base.replaceAll("/+$", "") + path);
        } catch (IllegalArgumentException e) {
            log.warn("license.api-base 不是合法地址: {}（{}）", base, e.getMessage());
            return null;
        }
        // "localhost:8787" 这种写法 URI.create 会把 localhost 解析成 scheme，
        // 所以不能只查 scheme 是否为 null，必须白名单校验
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            log.warn("license.api-base 不是合法的 http(s) 地址: {}", base);
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            String bodyText = response.body();
            JsonNode node = (bodyText == null || bodyText.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(bodyText);
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return node;
            }
            JsonNode error = node.path("error");
            throw new LicenseServerException(status,
                    error.path("code").asText("HTTP_" + status),
                    error.path("message").asText("服务端返回 " + status));
        } catch (LicenseServerException e) {
            throw e;
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("卡密服务端请求失败 | {} | {}", path, e.toString());
            return null;
        }
    }
}
