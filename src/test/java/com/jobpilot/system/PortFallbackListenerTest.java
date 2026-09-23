package com.jobpilot.system;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class PortFallbackListenerTest {

    @Test
    void 空闲端口原样返回() throws IOException {
        int free;
        try (ServerSocket socket = new ServerSocket(0)) {
            free = socket.getLocalPort();
        }
        assertThat(PortFallbackListener.resolvePort(free)).isEqualTo(free);
    }

    @Test
    void 被占用时顺延到下一个可用端口() throws IOException {
        // 显式绑 127.0.0.1 而不是裸 new ServerSocket(port)：裸构造绑的是通配地址，
        // Windows 上落到只监听 IPv6 的 :: socket，而 isListening 只连 127.0.0.1，
        // 占用探不到（CI 的 windows-latest 上实测挂过）。显式回环两个平台都能探到
        try (ServerSocket ignored = holdPort()) {
            int occupied = ignored.getLocalPort();
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
        try (ServerSocket holder = new ServerSocket()) {
            holder.bind(new InetSocketAddress("127.0.0.1", 0));
            int occupied = holder.getLocalPort();
            assertThat(PortFallbackListener.resolvePort(occupied)).isGreaterThan(occupied);
        }
    }

    @Test
    void 只绑回环地址的占用也要探到() throws IOException {
        // 同上：绑通配地址一样成功，但启动后打开的 http://127.0.0.1:<port>/
        // 落在回环监听者身上，用户看到的就是别人的页面
        try (ServerSocket holder = new ServerSocket()) {
            holder.bind(new InetSocketAddress("127.0.0.1", 0));
            int occupied = holder.getLocalPort();
            assertThat(PortFallbackListener.resolvePort(occupied)).isGreaterThan(occupied);
        }
    }

    @Test
    void 随机端口不干预() {
        assertThat(PortFallbackListener.resolvePort(0)).isZero();
    }

    /**
     * 先占一个端口再直接复用它。
     *
     * <p>不用「freePort() 拿端口 → 关掉 → 再绑」的两步：Windows 上关闭后的
     * 端口不会立刻回到可绑状态，两步之间隔着毫秒级间隔，实测 CI 上会抖。
     * 一步到位则没有这个窗口。
     */
    private static ServerSocket holdPort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        return socket;
    }

    private static ServerSocket bindLoopback(int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.bind(new InetSocketAddress("127.0.0.1", port));
        return socket;
    }
}
