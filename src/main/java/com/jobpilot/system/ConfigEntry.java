package com.jobpilot.system;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 键值配置表。卡密状态、AI 配置等运行时数据都存这里，避免每加一项配置就改表结构。
 */
@Data
@TableName("config")
public class ConfigEntry {

    @TableId(value = "config_key", type = IdType.INPUT)
    private String configKey;

    private String configValue;

    private String updatedAt;
}
