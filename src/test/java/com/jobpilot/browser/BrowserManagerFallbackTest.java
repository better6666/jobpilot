package com.jobpilot.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BrowserManagerFallbackTest {
    @Test
    void missingChromeFallsBackToEdgeWithIndependentProfile() throws Exception {
        BrowserManager manager = new BrowserManager(new BrowserManager.BrowserProperties());
        Playwright playwright = mock(Playwright.class);
        BrowserType chromium = mock(BrowserType.class);
        BrowserContext context = mock(BrowserContext.class);
        when(playwright.chromium()).thenReturn(chromium);
        List<String> channels = new ArrayList<>();
        List<Path> profiles = new ArrayList<>();
        when(chromium.launchPersistentContext(any(), any())).thenAnswer(call -> {
            var options = call.getArgument(1, BrowserType.LaunchPersistentContextOptions.class);
            String channel = (String) options.channel;
            channels.add(channel);
            profiles.add(call.getArgument(0, Path.class));
            if ("chrome".equals(channel)) {
                throw new PlaywrightException("Chrome is not installed");
            }
            return context;
        });
        Field driver = BrowserManager.class.getDeclaredField("playwright");
        driver.setAccessible(true);
        driver.set(manager, playwright);
        try {
            manager.submitAsync(() -> assertThat(manager.context()).isSameAs(context)).get(5, TimeUnit.SECONDS);
            assertThat(channels).containsExactly("chrome", "msedge");
            assertThat(profiles.get(0).getFileName().toString()).isEqualTo("browser-data");
            assertThat(profiles.get(1).getFileName().toString()).isEqualTo("browser-data-msedge");
        } finally {
            manager.shutdown();
        }
    }
}
