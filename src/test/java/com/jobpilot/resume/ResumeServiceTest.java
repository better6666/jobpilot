package com.jobpilot.resume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.ai.AiProperties;
import com.jobpilot.delivery.RunCoordinator;
import com.jobpilot.license.EntitlementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ResumeServiceTest {
    @TempDir Path home;
    private final ResumeAnalyzer analyzer = mock(ResumeAnalyzer.class);
    private final AiProperties ai = mock(AiProperties.class);
    private final EntitlementService entitlements = mock(EntitlementService.class);
    private final RunCoordinator coordinator = new RunCoordinator();
    private ResumeService service;
    private static final String CONTENT = "简历：Java 后端开发，使用 Spring Boot、MySQL 完成订单管理项目，负责接口开发、单元测试与部署。";

    @BeforeEach void setup() {
        when(entitlements.allows("ai_match")).thenReturn(true);
        when(entitlements.remainingToday("ai_analysis", "max_daily_ai_analysis")).thenReturn(10);
        service = new ResumeService(new ObjectMapper(), new ResumeTextExtractor(), analyzer, ai, entitlements, coordinator, home.resolve("简历目录"));
    }
    @AfterEach void close() { service.shutdown(); }

    static ResumeProfile profile() {
        return new ResumeProfile("有订单项目经验的 Java 开发者", List.of("Java 后端开发"), List.of("Java", "MySQL"),
                List.of("做过接口与测试"), List.of(new ResumeProfile.Improvement("高", "项目成果缺少可验证指标", "仅列出职责",
                "补充自己负责的范围与真实结果", "负责[真实模块]，完成[真实结果]")), List.of("毕业时间"), "Java 开发，做过 Spring Boot 订单项目，负责接口和测试。");
    }

    @Test void importPreservesFileBytesAndDoesNotCallAi() throws Exception {
        byte[] bytes = ResumeTextExtractorTest.pdf(true);
        Path original = home.resolve("原件.pdf"); Files.write(original, bytes);
        var document = service.importFile("原件.pdf", bytes, null);
        assertThat(Files.readAllBytes(original)).isEqualTo(bytes);
        assertThat(Files.readAllBytes(service.attachment(document.getId()))).isEqualTo(bytes);
        assertThat(service.list().items()).hasSize(1);
        verifyNoInteractions(analyzer);
        assertThatThrownBy(() -> service.get("../../elsewhere")).hasMessageContaining("无效");
    }

    @Test void replacementValidatesFirstAndUsesANewIdentity() {
        var old = service.importFile("旧版.txt", CONTENT.getBytes(StandardCharsets.UTF_8), null);
        assertThatThrownBy(() -> service.importFile("新.pdf", new byte[]{1, 2, 3}, old.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.get(old.getId()).getText()).isEqualTo(CONTENT);
        assertThatThrownBy(() -> service.importFile("另一个.txt", CONTENT.getBytes(StandardCharsets.UTF_8), null)).hasMessageContaining("最多");
        var next = service.importFile("新版本.txt", CONTENT.getBytes(StandardCharsets.UTF_8), old.getId());
        assertThat(next.getId()).isNotEqualTo(old.getId());
        assertThatThrownBy(() -> service.get(old.getId())).hasMessageContaining("不存在");
        assertThat(service.list().items()).hasSize(1);
    }

    @Test void analysisIsAsyncSingleFlightAndApplyingIsExplicit() throws Exception {
        var document = service.importFile("分析.txt", CONTENT.getBytes(StandardCharsets.UTF_8), null);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(analyzer.analyze(anyString(), anyString())).thenAnswer(call -> { entered.countDown(); release.await(2, TimeUnit.SECONDS); return profile(); });
        try {
            assertThat(service.analyze(document.getId(), "Java 后端").getAnalysisState()).isEqualTo("RUNNING");
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            service.analyze(document.getId(), "重复点击");
            assertThat(coordinator.current()).isEqualTo("简历分析");
            assertThatThrownBy(() -> service.updateText(document.getId(), "修改内容")).hasMessageContaining("分析进行中");
            verifyNoInteractions(ai);
        } finally { release.countDown(); }
        await(document.getId());
        assertThat(service.get(document.getId()).getAnalysisState()).isEqualTo("DONE");
        verify(analyzer, times(1)).analyze(CONTENT, "Java 后端");
        verify(entitlements, times(1)).recordUse("ai_analysis");
        service.applyPersona(document.getId());
        verify(ai).updatePersona(profile().persona());
    }

    @Test void quotaAndConcurrentDeliveryBlockAnalysisOrDeletion() {
        var document = service.importFile("简历.txt", CONTENT.getBytes(StandardCharsets.UTF_8), null);
        when(entitlements.remainingToday("ai_analysis", "max_daily_ai_analysis")).thenReturn(0);
        assertThatThrownBy(() -> service.analyze(document.getId(), "")).hasMessageContaining("额度");
        coordinator.acquire("boss");
        assertThatThrownBy(() -> service.delete(document.getId())).hasMessageContaining("boss");
        assertThat(service.get(document.getId()).getText()).isEqualTo(CONTENT);
        verifyNoInteractions(analyzer);
    }

    @Test void failuresStayFailuresAndTextChangesInvalidateOldProfile() throws Exception {
        var document = service.importFile("简历.txt", CONTENT.getBytes(StandardCharsets.UTF_8), null);
        when(analyzer.analyze(anyString(), anyString())).thenThrow(new IllegalStateException("AI 接口不可用"));
        service.analyze(document.getId(), ""); await(document.getId());
        var failed = service.get(document.getId());
        assertThat(failed.getAnalysisState()).isEqualTo("FAILED"); assertThat(failed.getProfile()).isNull();
        verify(entitlements, never()).recordUse(anyString());
        assertThat(coordinator.current()).isNull();
        doReturn(profile()).when(analyzer).analyze(anyString(), anyString());
        service.analyze(document.getId(), ""); await(document.getId());
        service.updateText(document.getId(), CONTENT + " 新补充的项目结果");
        assertThat(service.get(document.getId()).getProfile()).isNull();
        assertThatThrownBy(() -> service.applyPersona(document.getId())).hasMessageContaining("先完成");
    }

    private void await(String id) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (!"RUNNING".equals(service.get(id).getAnalysisState()) && coordinator.current() == null) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Analysis did not finish");
    }
}
