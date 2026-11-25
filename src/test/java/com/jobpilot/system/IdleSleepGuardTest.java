package com.jobpilot.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static com.jobpilot.system.IdleSleepGuard.ES_CONTINUOUS;
import static com.jobpilot.system.IdleSleepGuard.ES_SYSTEM_REQUIRED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdleSleepGuardTest {
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsKeepsOnlyTheSystemAwakeAndReleasesIt() {
        var kernel = IdleSleepGuard.Kernel32.INSTANCE;
        int original = kernel.SetThreadExecutionState(ES_CONTINUOUS);
        assertNotEquals(0, original);
        try {
            try (var guard = IdleSleepGuard.acquire()) {
                assertTrue(guard.active());
                int previous = kernel.SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED);
                assertEquals(ES_SYSTEM_REQUIRED, previous & 0x7fffffff, "只阻止系统睡眠，不持续点亮屏幕");
            }
            int released = kernel.SetThreadExecutionState(ES_CONTINUOUS);
            assertNotEquals(0, released);
            assertEquals(0, released & ES_SYSTEM_REQUIRED);
        } finally {
            kernel.SetThreadExecutionState(original | ES_CONTINUOUS);
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void macHoldsOnlyAnIdleSystemSleepAssertionUntilClosed() throws Exception {
        ProcessHandle helper;
        try (var guard = IdleSleepGuard.acquire()) {
            assertTrue(guard.active());
            helper = ProcessHandle.current().children()
                    .filter(process -> process.info().command().orElse("").equals("/usr/bin/caffeinate"))
                    .findFirst().orElseThrow();
            Process pmset = new ProcessBuilder("/usr/bin/pmset", "-g", "assertions").start();
            assertTrue(pmset.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, pmset.exitValue());
            String assertions = new String(pmset.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(assertions.lines().anyMatch(line -> line.contains("pid " + helper.pid() + "(caffeinate)")
                    && line.contains("PreventUserIdleSystemSleep")));
            assertFalse(assertions.lines().anyMatch(line -> line.contains("pid " + helper.pid() + "(caffeinate)")
                    && line.contains("DisplaySleep")));
        }
        helper.onExit().get(5, TimeUnit.SECONDS);
        assertFalse(helper.isAlive());
    }
}
