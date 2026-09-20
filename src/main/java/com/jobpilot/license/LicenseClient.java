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
     * @return 响应体 JSON；网络不可达返回 null，由调用方按宽限逻辑处理
     * @throws LicenseServerException 服务端返回了明确的业务错误
     */
    public JsonNode post(String apiBase, String path, Map<String, Object> body) {
        String url = (apiBase == null ? "" : apiBase.replaceAll("/+$", "")) + path;
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
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
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("卡密服务端请求失败 | {} | {}", path, e.toString());
            return null;
        }
    }
}
