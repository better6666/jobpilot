package com.jobpilot.system;

import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** 只在投递线程运行任务时向系统请求阻止空闲睡眠。 */
@Slf4j
public final class IdleSleepGuard implements AutoCloseable {

    static final int ES_CONTINUOUS = 0x80000000;
    static final int ES_SYSTEM_REQUIRED = 0x00000001;

    interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        int SetThreadExecutionState(int flags);
    }

    private final Runnable release;

    private IdleSleepGuard(Runnable release) {
        this.release = release;
    }

    /** Windows 的请求由调用线程持有，必须在同一线程释放。 */
    public static IdleSleepGuard acquire() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) return acquireMac();
            if (os.contains("win")) return acquireWindows();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException | LinkageError e) {
            log.warn("无法启用投递期间的系统防休眠：{}", e.getMessage());
        }
        return new IdleSleepGuard(null);
    }

    public boolean active() {
        return release != null;
    }

    @Override
    public void close() {
        if (active()) release.run();
    }

    private static IdleSleepGuard acquireMac() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("/usr/bin/caffeinate", "-i", "-w",
                Long.toString(ProcessHandle.current().pid()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (process.waitFor(150, TimeUnit.MILLISECONDS)) {
                throw new IOException("caffeinate 已退出，退出码 " + process.exitValue());
            }
        } catch (InterruptedException e) {
            process.destroy();
            throw e;
        }
        return new IdleSleepGuard(process::destroy);
    }

    private static IdleSleepGuard acquireWindows() {
        Kernel32 kernel = Kernel32.INSTANCE;
        int previous = kernel.SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED);
        if (previous == 0) throw new IllegalStateException("系统拒绝空闲防休眠请求");
        return new IdleSleepGuard(() -> {
            if (kernel.SetThreadExecutionState(previous | ES_CONTINUOUS) == 0) {
                log.warn("无法释放投递期间的系统防休眠");
            }
        });
    }
}
