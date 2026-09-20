package com.jobpilot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 完整启动链路上的端口顺延。
 *
 * 这条用例盯的是一个只在生产环境暴露的坑：配置的端口被占用时应用必须照样起来。
 * 以前踩过两次——一次是 SpringApplication.run(Class, String...) 是静态方法，
 * 调用它会另起一个 SpringApplication，addListeners 注册的顺延监听器被整个丢掉，
 * 表现为"双击 .app 什么都不发生"；另一次是 9527 被 dev 进程占着就直接启动失败。
 * 所以这里真起一遍应用，而不是只单测监听器本身。
 */
class JobPilotApplicationStartTest {

    @TempDir
    Path tempDir;

    private String savedDatasourceUrl;
    private String savedLogFile;
    private ConfigurableApplicationContext context;

    @BeforeEach
    void isolateDataDir() {
        // 单测数据隔离：预置系统属性，start() 里的 setIfAbsent 就不会把它们指到真数据目录
        savedDatasourceUrl = System.getProperty("spring.datasource.url");
        savedLogFile = System.getProperty("logging.file.name");
        System.setProperty("spring.datasource.url",
                "jdbc:sqlite:" + tempDir.resolve("jobpilot-test.db"));
        System.setProperty("logging.file.name",
                tempDir.resolve("logs").resolve("jobpilot-test.log").toString());
    }

    @AfterEach
    void shutDown() {
        if (context != null) {
            context.close();
        }
        restore("spring.datasource.url", savedDatasourceUrl);
        restore("logging.file.name", savedLogFile);
    }

    @Test
    void 配置端口被占用时应用照样起来并换到可用端口() throws Exception {
        int occupied = freePort();
        try (ServerSocket holder = new ServerSocket(occupied)) {
            context = JobPilotApplication.start(new String[]{
                    "--server.port=" + occupied,
                    // 别在跑测试的机器上弹浏览器
                    "--jobpilot.open-page=false"
            });
        }

        assertThat(context.isRunning()).isTrue();
        assertThat(context).isInstanceOf(WebServerApplicationContext.class);
        int actual = ((WebServerApplicationContext) context).getWebServer().getPort();
        assertThat(actual).isNotEqualTo(occupied);
        assertThat(actual).isPositive();
    }

    @Test
    void 端口空闲时原样使用不擅自改() throws Exception {
        int requested = freePort();
        context = JobPilotApplication.start(new String[]{
                "--server.port=" + requested,
                "--jobpilot.open-page=false"
        });

        assertThat(context.isRunning()).isTrue();
        int actual = ((WebServerApplicationContext) context).getWebServer().getPort();
        assertThat(actual).isEqualTo(requested);
    }

    /** 找一个当前空闲的端口：先绑上再放开，随后拿它当"被占用"或"要用"的端口 */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
