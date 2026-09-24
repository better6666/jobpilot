package com.jobpilot.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void 套餐读取遇到暂时的404会重试() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/plans", exchange -> {
            boolean ready = calls.incrementAndGet() > 1;
            byte[] body = (ready ? "{\"success\":true,\"data\":{\"plans\":[]}}" : "Not Found")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(ready ? 200 : 404, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertTrue(client.get(base, "/api/plans").path("success").asBoolean());
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }
}
