package com.jobpilot.delivery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台筛选项码表（城市、薪资、经验、学历…）。
 *
 * 数据来自各平台官网页面自己的下拉选项，打包成 JSON 随 jar 发布——不依赖任何
 * 第三方接口，离线可用，也不会因为平台改接口而失效。
 *
 * 四个平台的码表互不相通：Boss 城市是 9 位（101020100），猎聘是内部码（010），
 * 51job 是 6 位（010000），智联又是另一套（530）。所以一个平台一个 JSON，
 * 由子类把资源名传进来，加载逻辑只此一份。
 */
@Slf4j
public abstract class PlatformOptions {

    private final String resource;
    private final ObjectMapper objectMapper;
    private Map<String, List<Option>> options = new LinkedHashMap<>();

    protected PlatformOptions(String resource, ObjectMapper objectMapper) {
        this.resource = resource;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void load() {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            options = objectMapper.readValue(in, new TypeReference<>() {
            });
            log.info("{} 选项码表已加载: {} 个城市, 其余筛选 {} 组",
                    resource, options.getOrDefault("city", List.of()).size(), Math.max(0, options.size() - 1));
        } catch (Exception e) {
            log.error("加载 {} 失败，管理页筛选项将不可用", resource, e);
        }
    }

    public List<Option> cities() {
        return options.getOrDefault("city", List.of());
    }

    public List<Option> filters(String type) {
        return options.getOrDefault(type, List.of());
    }

    /** 有哪些筛选组（管理页据此渲染下拉框） */
    public List<String> filterTypes() {
        return options.keySet().stream().filter(t -> !"city".equals(t)).toList();
    }

    /** 城市名 -> 码。用户配置里写"上海"也行，驱动层负责换成码 */
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
