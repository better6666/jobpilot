package com.jobpilot.update;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class UpdateManifestTest {
    @Test
    void comparesNumericVersionComponentsAndRejectsDowngrades() {
        assertThat(UpdateManifest.newer("1.0.100", "1.0.99")).isTrue();
        assertThat(UpdateManifest.newer("1.0.23", "1.0.23")).isFalse();
        assertThat(UpdateManifest.newer("1.0.22", "1.0.23")).isFalse();
        assertThat(UpdateManifest.newer("1.0.24", "开发版本")).isFalse();
        assertThat(UpdateManifest.newer("1.0.24-beta", "1.0.23")).isFalse();
    }

    @Test
    void packageMustUseTheManifestOriginAndDeclareItsDigestAndSize() {
        URI origin = URI.create("https://getjobs.dpdns.org/updates.json");
        var good = new UpdateManifest("1.0.23", "2026-11-18", List.of("更新说明"),
                Map.of("windows-x64", new UpdateManifest.Asset("https://getjobs.dpdns.org/download/package.zip", "a".repeat(64), 100)));
        assertThatCode(() -> good.validate(origin)).doesNotThrowAnyException();
        var unsafe = new UpdateManifest("1.0.23", "2026-11-18", List.of("更新说明"),
                Map.of("windows-x64", new UpdateManifest.Asset("https://another.example/package.zip", "a".repeat(64), 100)));
        assertThatThrownBy(() -> unsafe.validate(origin)).isInstanceOf(IllegalArgumentException.class);
    }
}
