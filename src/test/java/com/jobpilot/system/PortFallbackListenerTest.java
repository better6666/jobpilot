package com.jobpilot.system;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class PortFallbackListenerTest {

    @Test
    void 空闲端口原样返回() throws IOException {
        int free = freePort();
        assertThat(PortFallbackListener.resolvePort(free)).isEqualTo(free);
    }

    @Test
    void 被占用时顺延到下一个可用端口() throws IOException {
        int occupied = freePort();
        try (ServerSocket ignored = new ServerSocket(occupied)) {
            int resolved = PortFallbackListener.resolvePort(occupied);
            assertThat(resolved).isGreaterThan(occupied);
            // 顺延到的端口必须真的能绑上
            try (ServerSocket probe = new ServerSocket(resolved)) {
                assertThat(probe.isBound()).isTrue();
            }
        }
    }

    @Test
    void 只绑通配IPv4地址的占用也要探到() throws IOException {
        // mac 上 Java 的通配绑定落到双栈 IPv6 socket，和只绑 0.0.0.0 的进程
        // 井水不犯河水：绑得上，但发往 127.0.0.1 的请求全被 IPv4 那个接走
        int occupied = freePort();
        try (ServerSocket holder = new ServerSocket()) {
            holder.bind(new InetSocketAddress("0.0.0.0", occupied));
            assertThat(PortFallbackListener.resolvePort(occupied)).isGreaterThan(occupied);
        }
    }

    @Test
    void 只绑回环地址的占用也要探到() throws IOException {
        // 同上：绑通配地址一样成功，但启动后打开的 http://127.0.0.1:<port>/
        // 落在回环监听者身上，用户看到的就是别人的页面
        int occupied = freePort();
        try (ServerSocket holder = new ServerSocket()) {
            holder.bind(new InetSocketAddress("127.0.0.1", occupied));
            assertThat(PortFallbackListener.resolvePort(occupied)).isGreaterThan(occupied);
        }
    }

    @Test
    void 随机端口不干预() {
        assertThat(PortFallbackListener.resolvePort(0)).isZero();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
