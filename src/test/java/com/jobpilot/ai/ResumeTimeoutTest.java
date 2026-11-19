package com.jobpilot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResumeTimeoutTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void 完整分析独立等待且只有平台请求携带用途() throws Exception {
        HttpClient transport = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"分析\"}}],\"success\":true,\"data\":{\"text\":\"分析\"}}");
        when(transport.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        AiService ai = new AiService(mapper, transport);
        assertThat(ai.chat("https://relay.test", "test-key", "m", "s", "u", .2).isOk()).isTrue();
        assertThat(ai.chatResume("https://relay.test", "test-key", "m", "s", "u", .2).isOk()).isTrue();
        assertThat(ai.chatPlatform("https://license.test", "test-token", "device", "s", "u", .2, "m").isOk()).isTrue();
        assertThat(ai.chatResumePlatform("https://license.test", "test-token", "device", "s", "u", .2, "m").isOk()).isTrue();
        var requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(4)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        var all = requests.getAllValues();
        for (int i = 0; i < all.size(); i++) {
            assertThat(all.get(i).timeout()).contains(Duration.ofSeconds(i % 2 == 0 ? 45 : 150));
            var body = mapper.readTree(body(all.get(i)));
            if (i == 3) {
                assertThat(body.path("purpose").asText()).isEqualTo("resume_analysis");
                assertThat(body.path("model").asText()).isEqualTo("m");
            } else {
                assertThat(body.has("purpose")).isFalse();
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 完整分析长超时不重复扣时重试() throws Exception {
        HttpClient transport = mock(HttpClient.class);
        when(transport.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("test timeout"));
        AiService ai = new AiService(mapper, transport);
        assertThat(ai.chatResume("https://relay.test", "test-key", "m", "s", "u", .2).isOk()).isFalse();
        assertThat(ai.chatResumePlatform("https://license.test", "test-token", "device", "s", "u", .2, "m").isOk()).isFalse();
        verify(transport, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 服务端超时返回502也不能重复提交完整简历() throws Exception {
        HttpClient transport = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(502);
        when(response.body()).thenReturn("{\"success\":false,\"message\":\"模型超时\"}");
        when(transport.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        AiService ai = new AiService(mapper, transport);
        assertThat(ai.chatResumePlatform("https://license.test", "test-token", "device", "s", "u", .2, "m").isOk()).isFalse();
        verify(transport).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private static String body(HttpRequest request) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var result = new CompletableFuture<String>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            public void onError(Throwable error) { result.completeExceptionally(error); }
            public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return result.get();
    }
}
