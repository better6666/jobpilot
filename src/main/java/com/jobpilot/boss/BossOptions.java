package com.jobpilot.boss;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boss 筛选选项码表（城市、薪资、经验、学历、规模、融资阶段、职位类型）。
 *
 * 数据来自 Boss 官网页面自己的下拉选项，打包成 boss-options.json 随 jar 发布——
 * 不依赖任何第三方接口，离线可用，也不会因为 Boss 改接口而失效。
 */
@Slf4j
@Component
public class BossOptions {

    private final ObjectMapper objectMapper;
    private Map<String, List<Option>> options = new LinkedHashMap<>();

    public BossOptions(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void load() {
        try (InputStream in = new ClassPathResource("boss-options.json").getInputStream()) {
            options = objectMapper.readValue(in, new TypeReference<>() {
            });
            log.info("Boss 选项码表已加载: {} 个城市, 薪资/经验/学历等 {} 组筛选",
                    options.getOrDefault("city", List.of()).size(), options.size() - 1);
        } catch (Exception e) {
            log.error("加载 boss-options.json 失败，管理页筛选项将不可用", e);
        }
    }

    public List<Option> cities() {
        return options.getOrDefault("city", List.of());
    }

    public List<Option> filters(String type) {
        return options.getOrDefault(type, List.of());
    }

    /** 城市名 -> 码。用户配关键词时写"上海"也行，驱动层负责换成码 */
    public String cityCode(String cityName) {
        if (cityName == null || cityName.isBlank()) {
            return null;
        }
        String trimmed = cityName.trim();
        for (Option option : cities()) {
            if (option.getName().equals(trimmed)) {
                return option.getCode();
            }
        }
        return null;
    }

    @Data
    public static class Option {
        private String name;
        private String code;
    }
}
