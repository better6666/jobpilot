package com.jobpilot;

import com.jobpilot.browser.PlaywrightDriverSupport;
import com.jobpilot.system.AppWindowBootstrap;
import com.jobpilot.system.PortFallbackListener;
import com.jobpilot.system.SystemPaths;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class JobPilotApplication {

    public static void main(String[] args) throws IOException {
        start(args);
    }

    /**
     * 真正干活的启动路径，从 main() 里拆出来是为了能单测。
     *
     * "端口被占时应用照样起来"这个行为只有在完整启动链路上才看得见，
     * 而 main() 自己没法断言，所以留一个能直接调的入口。
     */
    static ConfigurableApplicationContext start(String[] args) throws IOException {
        // 数据目录一律落用户目录（见 SystemPaths）：双击 .app / exe 启动时工作目录是 /
        // 或 System32，相对路径必崩。SQLite 不会自建目录，Hikari 建连又早于建表，
        // 所以启动前先把目录建好。
        Path dataDir = SystemPaths.dataDir();
        Files.createDirectories(dataDir);
        Files.createDirectories(dataDir.resolve("logs"));

        // 数据库与日志的绝对路径走系统属性：优先级高于 application.yaml 的默认值，
        // 又低于命令行参数，开发时 --spring.datasource.url=... 仍可覆盖。
        // 已经设过就不动：单测靠这个把库和日志指到临时目录，别去动真数据。
        setIfAbsent("spring.datasource.url",
                "jdbc:sqlite:" + dataDir.resolve("jobpilot.db"));
        setIfAbsent("logging.file.name",
                dataDir.resolve("logs").resolve("jobpilot.log").toString());

        // patchright driver 定位：dev 下 gradle bootRun 会带 -Dplaywright.cli.dir，
        // 打包版（jpackage）没有 gradle，只能自己找——发布时把 driver 复制到
        // fat jar 同级的 driver/ 目录（macOS 在 .app/Contents/app/driver/）。
        // 必须在 Playwright.create() 之前，即 Spring 启动阶段之前完成。
        com.jobpilot.browser.PlaywrightDriverSupport.ensureDriverDir();

        SpringApplication app = new SpringApplication(JobPilotApplication.class);
        // 端口被占时顺延，并在服务就绪后显示原生 Swing 主窗口。
        // 必须调实例方法 run(args)：run(Class, String...) 是静态方法，会另起一个
        // SpringApplication，这里 addListeners 注册的监听器会被整个丢掉（踩过）。
        // Spring Boot 的 SpringApplication.headless 出厂是 true，configureHeadlessProperty()
        // 会把 java.awt.headless 显式设成 "true"——桌面窗口就永远出不来
        // （GraphicsEnvironment 构造时缓存这个值，之后再改属性也没用）。
        // 必须在 run() 之前调，它只在属性未设置时才沿用传入值。
        app.setHeadless(false);
        app.addListeners(new PortFallbackListener());
        app.addListeners(new AppWindowBootstrap());
        return app.run(args);
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}
