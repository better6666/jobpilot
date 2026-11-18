package com.jobpilot.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BundledBrowserTest {
    @TempDir
    Path directory;

    @Test
    void resolvesRelativePathsContainingSpaces() throws Exception {
        Path executable = directory.resolve("chrome mac/App.app/Contents/MacOS/Browser");
        Files.createDirectories(executable.getParent());
        Files.createFile(executable);
        Files.writeString(directory.resolve("executable.txt"), directory.relativize(executable).toString());
        assertThat(BundledBrowser.executable(directory)).isEqualTo(executable);
    }

    @Test
    void ignoresMissingAndOutOfBundleExecutables() throws Exception {
        assertThat(BundledBrowser.executable(directory)).isNull();
        Files.writeString(directory.resolve("executable.txt"), "missing/chrome.exe");
        assertThat(BundledBrowser.executable(directory)).isNull();
        Files.writeString(directory.resolve("executable.txt"), "../outside.exe");
        assertThat(BundledBrowser.executable(directory)).isNull();
    }
}
