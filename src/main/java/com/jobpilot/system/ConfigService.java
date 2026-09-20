package com.jobpilot.system;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    public static final String LICENSE_KEY = "license";

    private final ConfigMapper configMapper;
    private final ObjectMapper objectMapper;

    public String get(String key) {
        ConfigEntry entry = configMapper.selectById(key);
        return entry == null ? null : entry.getConfigValue();
    }

    public void set(String key, String value) {
        ConfigEntry entry = configMapper.selectById(key);
        if (entry == null) {
            entry = new ConfigEntry();
            entry.setConfigKey(key);
            entry.setConfigValue(value);
            entry.setUpdatedAt(Instant.now().toString());
            configMapper.insert(entry);
        } else {
            entry.setConfigValue(value);
            entry.setUpdatedAt(Instant.now().toString());
            configMapper.updateById(entry);
        }
    }

    public <T> T getJson(String key, Class<T> type, T defaultValue) {
        String raw = get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return objectMapper.readValue(raw, type);
        } catch (Exception e) {
            log.warn("配置 {} 解析失败，使用默认值: {}", key, e.getMessage());
            return defaultValue;
        }
    }

    public <T> void setJson(String key, T value) {
        try {
            set(key, objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("配置序列化失败: " + key, e);
        }
    }
}
