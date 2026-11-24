package com.jobpilot.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ResumePanelTest {
    @Test
    void 导入成功立即选中新简历并可以分析(@TempDir Path temp) throws Exception {
        var mapper = new ObjectMapper();
        var imported = mapper.readTree("""
                {"id":"new-resume","name":"简历.txt","size":128,"analysisState":"IDLE",
                 "text":"我曾负责订单系统开发和日常维护，交付多项用户反馈改进，掌握接口设计和数据库优化。"}
                """);
        byte[] response = mapper.createObjectNode().put("success", true).set("data", imported)
                .toString().getBytes(StandardCharsets.UTF_8);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/resumes/import", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            Path file = Files.writeString(temp.resolve("简历.txt"), imported.path("text").asText());
            var selected = new CountDownLatch(1);
            var analyzed = new CountDownLatch(1);
            JPanel[] panel = new JPanel[1];
            SwingUtilities.invokeAndWait(() -> {
                var ui = new ResumePanel("http://127.0.0.1:" + server.getAddress().getPort(), (method, path, body) -> {
                    assertEquals("POST", method);
                    assertEquals("/api/resumes/new-resume/analyze", path);
                    analyzed.countDown();
                    var running = (com.fasterxml.jackson.databind.node.ObjectNode) imported.deepCopy();
                    running.put("analysisState", "RUNNING");
                    return running;
                }, p -> {}, selected::countDown);
                panel[0] = ui;
                assertFalse(button(ui, "AI 分析画像").isEnabled());
                ui.importFile(file, "");
            });
            assertTrue(selected.await(10, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                JButton analyze = button(panel[0], "AI 分析画像");
                assertTrue(analyze.isEnabled());
                analyze.doClick();
            });
            assertTrue(analyzed.await(10, TimeUnit.SECONDS));
        } finally {
            server.stop(0);
        }
    }

    private static JButton button(Container root, String label) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && label.equals(button.getText())) return button;
            if (child instanceof Container container) {
                JButton found = button(container, label);
                if (found != null) return found;
            }
        }
        return null;
    }
}
