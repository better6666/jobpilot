package com.jobpilot.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LicenseClient 的地址健壮性：配置缺失/写错时不能把异常抛到 Spring 启动链上
 * （URI with undefined scheme 曾让整个应用起不来），一律按"服务端不可达"降级。
 */
class LicenseClientTest {

    private LicenseClient client;

    @BeforeEach
    void setUp() {
        client = new LicenseClient(new ObjectMapper());
    }

    @Test
    void apiBase为空时返回null不抛异常() {
        assertNull(client.post("", "/verify", Map.of("token", "t")));
        assertNull(client.post("   ", "/verify", Map.of("token", "t")));
        assertNull(client.post(null, "/verify", Map.of("token", "t")));
    }

    @Test
    void apiBase缺少协议头时返回null不抛异常() {
        assertNull(client.post("localhost:8787", "/verify", Map.of("token", "t")));
        assertNull(client.post("jobpilot.example.com", "/verify", Map.of("token", "t")));
    }

    @Test
    void apiBase不是合法URI时返回null不抛异常() {
        assertNull(client.post("http://[坏地址", "/verify", Map.of("token", "t")));
    }

    @Test
    void 连不上的合法地址返回null不抛异常() {
        // 127.0.0.1:1 是保留未监听端口，必然连接失败
        assertNull(client.post("http://127.0.0.1:1", "/verify", Map.of("token", "t")));
    }
}
