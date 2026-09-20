package com.jobpilot;

import com.jobpilot.system.SystemPaths;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class JobPilotApplication {

    public static void main(String[] args) throws IOException {
        // SQLite 不会自动创建库文件所在目录，而 Hikari 连接池在 Bean 创建阶段就要建连，
        // 目录不存在会直接让启动失败（SchemaInitializer 建表跑得更晚，救不了场）。
        // 所以必须在 Spring 启动之前把目录建好。
        Files.createDirectories(SystemPaths.dataDir());
        SpringApplication.run(JobPilotApplication.class, args);
    }
}
