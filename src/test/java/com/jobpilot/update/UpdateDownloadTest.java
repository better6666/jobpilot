package com.jobpilot.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.delivery.RunCoordinator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class UpdateDownloadTest {
    @TempDir Path temporary;

    @Test
    void corruptedDownloadIsRejectedBeforeTouchingTheInstalledProgram() throws Exception {
        byte[] data = "verified installer bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/package.zip", exchange -> {
            exchange.sendResponseHeaders(200, data.length);
            try (var output = exchange.getResponseBody()) { output.write(data); }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/package.zip";
        var service = new UpdateService(new ObjectMapper(), new RunCoordinator(), new DefaultApplicationArguments(),
                mock(ServletWebServerApplicationContext.class), URI.create(url));
        Path installed = temporary.resolve("installed.exe");
        Files.writeString(installed, "previous app");
        Path downloaded = temporary.resolve("package.zip");
        try {
            assertThatThrownBy(() -> service.download(new UpdateManifest.Asset(url, "0".repeat(64), data.length), downloaded))
                    .hasMessageContaining("校验失败");
            assertThat(downloaded).doesNotExist();
            assertThat(temporary.resolve("package.zip.part")).doesNotExist();
            assertThat(Files.readString(installed)).isEqualTo("previous app");
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            service.download(new UpdateManifest.Asset(url, digest, data.length), downloaded);
            assertThat(Files.readAllBytes(downloaded)).isEqualTo(data);
        } finally {
            service.shutdown();
            server.stop(0);
        }
    }
}
