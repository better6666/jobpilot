package com.jobpilot.system;

import java.nio.file.Path;

/**
 * 运行时文件路径的唯一出处。
 */
public final class SystemPaths {

    private SystemPaths() {
    }

    /** 数据库与运行时文件目录（相对于进程工作目录） */
    public static Path dataDir() {
        return Path.of("db");
    }
}
