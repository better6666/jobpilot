package com.jobpilot;

import com.jobpilot.system.SystemPaths;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class JobPilotApplication {

    public static void main(String[] args) throws IOException {
        // 数据目录一律落用户目录（见 SystemPaths）：双击 .app / exe 启动时工作目录是 /
        // 或 System32，相对路径必崩。SQLite 不会自建目录，Hikari 建连又早于建表，
        // 所以启动前先把目录建好。
        Path dataDir = SystemPaths.dataDir();
        Files.createDirectories(dataDir);
        Files.createDirectories(dataDir.resolve("logs"));

        // 数据库与日志的绝对路径走系统属性：优先级高于 application.yaml 的默认值，
        // 又低于命令行参数，开发时 --spring.datasource.url=... 仍可覆盖。
        System.setProperty("spring.datasource.url",
                "jdbc:sqlite:" + dataDir.resolve("jobpilot.db"));
        System.setProperty("logging.file.name",
                dataDir.resolve("logs").resolve("jobpilot.log").toString());

        SpringApplication.run(JobPilotApplication.class, args);
    }
}
