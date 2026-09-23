package com.jobpilot.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

/**
 * 配置的端口被占用就往后顺延。
 *
 * 9527 被别的程序占着是常态（上次没退干净的旧进程、用户自己开的服务），
 * 而直接启动失败对最终用户是隐形的：双击 .app / exe 时 stdout 没有地方去，
 * 报错只落在用户目录的 logs/ 里，用户看到的就是"双击了没反应"。
 * 所以在环境准备好之后、Web 服务器绑定之前换一个能用的端口。
 */
public class PortFallbackListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Logger log = LoggerFactory.getLogger(PortFallbackListener.class);

    /** 顺延探测范围：够覆盖"旧进程没退干净"这种常见情况，又不至于漫无目的 */
    private static final int MAX_PROBES = 100;

    private static final String LOOPBACK_HOST = "127.0.0.1";

    /** 回环连接探测的超时：回环上"没人听"是立刻 refused 的，只有异常环境才会等满超时 */
    // 200ms 本机够用，但 CI 的 Windows runner 满载时回环连接也会超：
    // 一旦超时就被当成"没人听"，占用探不到，端口顺延失效（实测挂过）。
    // 500ms 对启动时那几次探测仍然无感
    private static final int CONNECT_TIMEOUT_MS = 500;

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        String configured = event.getEnvironment().getProperty("server.port", "9527");
        int requested;
        try {
            requested = Integer.parseInt(configured.trim());
        } catch (NumberFormatException e) {
            // 0（随机端口）或非法值都不干预，交给 Spring 自己处理
            return;
        }
        int actual = resolvePort(requested);
        if (actual == requested) {
            return;
        }
        log.warn("端口 {} 已被占用，改到 {}", requested, actual);
        // addFirst：盖过 application.yaml 与命令行之外的任何来源，
        // 但显式传了 --server.port 的场景走不到这（那个端口空闲才会到这），
        // 所以不存在"用户指定了还被改"的情况
        event.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("jobpilotPortFallback",
                        Map.of("server.port", String.valueOf(actual))));
    }

    /** 端口可用就原样返回，否则返回顺延后第一个可用的；都占着返回原值让 Spring 报错 */
    static int resolvePort(int requested) {
        if (requested == 0 || isAvailable(requested)) {
            return requested;
        }
        for (int port = requested + 1; port < requested + MAX_PROBES; port++) {
            if (isAvailable(port)) {
                return port;
            }
        }
        return requested;
    }

    private static boolean isAvailable(int port) {
        // 两层探测，缺一不可：
        // 1. 绑通配地址——和 Tomcat 的绑法一致，能挡住同样绑通配地址的进程，
        //    最常见的就是上一次没退干净的 JobPilot 自己
        // 2. 连回环——macOS 上 Java 的通配绑定会落到双栈 IPv6 socket，只绑
        //    0.0.0.0 或只绑 127.0.0.1 的进程它一律探不到：两边都 listen、
        //    结果是谁都连不上。而启动后打开的正是本机页面，这种占用一样得让位
        //    （实测：Python 占着 0.0.0.0:9527 时通配绑定照样成功，app 起来后
        //    9527 和 9528 都连不上）
        return canBind(port) && !isListening(port);
    }

    private static boolean canBind(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            // 不设 SO_REUSEADDR：要探测的就是"现在有没有人正在监听"
            socket.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 回环上有没有人在听：connect 能通就是有。
     *
     * 只看 127.0.0.1，别顺带探 ::1：实测在开着代理/VPN 的 mac 上，连一个肯定没人
     * 监听的 ::1 端口也连得通（连接被中间层接走，接着 read 超时），于是每个端口都
     * 误报成"被占用"，顺延逻辑整个失效——那比漏探严重得多。而启动后打开的本机
     * 页面走的就是 127.0.0.1，探这个地址正好够。
     */
    private static boolean isListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK_HOST, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            // 没人听（connection refused）或超时，都算这个地址上没占用
            return false;
        }
    }
}
