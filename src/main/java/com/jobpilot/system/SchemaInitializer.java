package com.jobpilot.system;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 建表器。必须赶在所有业务 Bean 之前执行：LicenseService 的 @PostConstruct 要读 config 表，
 * 而它是 @Component 依赖链上的一环，晚一步就会报 no such table。
 * 通过 @DependsOn("schemaInitializer") 把顺序钉死。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        Path dbDir = SystemPaths.dataDir();
        try {
            if (Files.notExists(dbDir)) {
                Files.createDirectories(dbDir);
                log.info("已创建数据库目录: {}", dbDir);
            }
        } catch (Exception e) {
            log.warn("创建数据库目录失败: {}", dbDir, e);
        }
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS config (
                    config_key   TEXT PRIMARY KEY,
                    config_value TEXT,
                    updated_at   TEXT
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS deliveries (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    platform        TEXT NOT NULL,
                    keyword         TEXT,
                    encrypt_id      TEXT,
                    encrypt_user_id TEXT,
                    job_name        TEXT,
                    brand_name      TEXT,
                    salary_desc     TEXT,
                    city_name       TEXT,
                    area_district   TEXT,
                    job_experience  TEXT,
                    job_degree      TEXT,
                    boss_name       TEXT,
                    boss_title      TEXT,
                    job_url         TEXT,
                    greeting        TEXT,
                    delivery_status TEXT,
                    fail_reason     TEXT,
                    score           INTEGER,
                    created_at      TEXT,
                    updated_at      TEXT
                )
                """);
        // 同一岗位同一 HR 只允许一条记录：单线程投递本就不会重复，
        // 唯一索引兜底防止同一轮里列表接口重复返回同一卡片
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_deliveries_job
                    ON deliveries(platform, encrypt_id, encrypt_user_id)
                """);
        log.info("数据库结构就绪");
    }
}
