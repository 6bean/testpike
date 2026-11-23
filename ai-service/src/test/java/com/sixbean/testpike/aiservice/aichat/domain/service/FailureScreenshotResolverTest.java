package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.aichat.domain.component.FailureDiagnosis;
import com.sixbean.testpike.aiservice.aichat.domain.component.FailureScreenshotBytesFetcher;
import com.sixbean.testpike.aiservice.aichat.domain.component.FailureScreenshotEvidence;
import com.sixbean.testpike.aiservice.aichat.domain.component.FailureScenePromptTemplate;
import com.sixbean.testpike.aiservice.aichat.infrastructure.remote.TaskScreenshotView;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.testsupport.AiCallLogTestCleanup;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrFallbackOutcome;
import com.sixbean.testpike.aiservice.ocr.domain.service.OcrTextFallbackReader;
import com.sixbean.testpike.aiservice.repair.infrastructure.remote.RepairTaskView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Failure-scene screenshot round-trip contract
 * ladder. Real DB posture identical to VisualLocateServiceImplTest: the call
 * log row is written for real, only the core door and the provider chain are
 * isolated (L2 infrastructure isolation — the real wiring lands on the L3
 * live ladder).
 * <p>Assertions:
 * ① sub-switch off (ai.vision.failure-screenshot != 1) = silent NONE — zero
 * artifact reads, zero model calls, no frames (off = deterministic chain,
 * no half-baked side effects);
 * ② sub-switch on + master gate off = degraded with the rejection naming the
 * literal key path (R32 honest guidance: where to turn it on, by name);
 * ③ both on + visual model key blank = degraded naming ai.vision.model —
 * never fall back to the text router pretending vision;
 * ④ both on + model set + no screenshot artifact = degraded, no artifact was
 * there to describe (named honestly, object list read through the door);
 * ⑤ full happy path = presigned url rides a vision ChatMessage, model text
 * becomes the scene block (length-bounded), trace anchor carries the object
 * key, tp_ai_call_log has one real row (function = dialog scene vision);
 * ⑥ provider failure = degraded (never breaks the text-only answer);
 * ⑦ diagnosis not resolved (NONE input) = NONE without touching anything.
 */
@SpringBootTest
class FailureScreenshotResolverTest {

    private static final long PROJECT_BASE = 989_130_000L;
    private static final long PROJECT = PROJECT_BASE + 1L;

    @Autowired
    private FailureScreenshotResolver resolver;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CoreGateway coreGateway;

    @MockitoBean
    private LlmFallbackChain llmFallbackChain;

    @MockitoBean
    private FailureScreenshotBytesFetcher bytesFetcher;

    @MockitoBean
    private OcrTextFallbackReader fallbackReader;

    @AfterEach
    void cleanup() {
        AiCallLogTestCleanup.purgeProjectRange(jdbcTemplate, PROJECT_BASE);
    }

    private static FailureDiagnosis resolvedDiagnosis() {
        return FailureDiagnosis.resolved(55L, 11L, "元素未找到：#submit-order",
                "[E] 执行任务 #55（用例 #11，状态=失败）\n失败详情：元素未找到：#submit-order");
    }

    private void switches(String master, String sub) {
        when(coreGateway.getSysConfig("ai.vision.enabled")).thenReturn(master);
        when(coreGateway.getSysConfig("ai.vision.failure-screenshot")).thenReturn(sub);
    }

    @Test
    void subSwitchOffIsSilentNoneWithZeroArtifactReadsAndZeroModelCalls() {
        switches("1", "0");

        FailureScreenshotEvidence evidence = resolver.resolve(PROJECT, resolvedDiagnosis());

        assertFalse(evidence.attached());
        assertFalse(evidence.absent());
        assertEquals(FailureScreenshotEvidence.NONE, evidence);
        verifyNoInteractions(llmFallbackChain);
        verify(coreGateway, never()).taskScreenshots(anyLong());
    }

    @Test
    void masterGateOffDegradesHonestlyNamingTheLiteralKeyPath() {
        switches("0", "1");

        FailureScreenshotEvidence evidence = resolver.resolve(PROJECT, resolvedDiagnosis());

        assertTrue(evidence.absent(), evidence.absentReason());
        assertTrue(evidence.absentReason().contains("ai.vision.enabled"), evidence.absentReason());
        verifyNoInteractions(llmFallbackChain);
    }

    @Test
    void blankVisionModelDegradesNamingModelKeyInsteadOfPretendingWithTextModel() {
        switches("1", "1");
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn(" ");

        FailureScreenshotEvidence evidence = resolver.resolve(PROJECT, resolvedDiagnosis());

        assertTrue(evidence.absent());
        assertTrue(evidence.absentReason().contains("ai.vision.model"), evidence.absentReason());
        verifyNoInteractions(llmFallbackChain);
    }

    @Test
    void taskWithoutScreenshotArtifactDegradesWithoutAnyModelCall() {
        switches("1", "1");
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("glm-vision-test");
        when(coreGateway.taskScreenshots(55L)).thenReturn(List.of());

        FailureScreenshotEvidence evidence = resolver.resolve(PROJECT, resolvedDiagnosis());

        assertTrue(evidence.absent());
        assertTrue(evidence.absentReason().contains("截图"), evidence.absentReason());
        verifyNoInteractions(llmFallbackChain);
    }

    @Test
    void happyPathSendsPresignedUrlAsVisionPartAndLandsSceneBlockPlusRealCallLogRow() {
        switches("1", "1");
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("glm-vision-test");
        when(coreGateway.taskScreenshots(55L)).thenReturn(List.of(new TaskScreenshotView(
                55L, "proj/7/screenshot/fail-3.png",
                "http://s3.local/presigned?X-Sig=abc")));
        when(bytesFetcher.fetch(eq("http://s3.local/presigned?X-Sig=abc"), anyInt()))
                .thenReturn(new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4});
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), eq(PROJECT), isNull()))
                .thenReturn(new LlmChatHandle(new LlmChatResult(
                        "页面停留在订单列表，弹窗提示“服务超时”，提交按钮置灰", "test-provider",
                        "glm-vision-test", 300, 40, 800L),
                        new LlmEndpointConfig("test-provider", "glm-vision-test", "k",
                                "http://x", "config")));

        FailureScreenshotEvidence evidence = resolver.resolve(PROJECT, resolvedDiagnosis());

        assertTrue(evidence.attached(), "scene block expected");
        assertTrue(evidence.sceneBlock().contains("服务超时"), evidence.sceneBlock());
        assertEquals("proj/7/screenshot/fail-3.png", evidence.objectKey());
        ArgumentCaptor<ChatCompletionRequest> request =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain).chatHandle(request.capture(), eq(PROJECT), isNull());
        ChatMessage user = request.getValue().messages().stream()
                .filter(m -> "user".equals(m.role())).findFirst().orElseThrow();
        assertEquals("glm-vision-test", request.getValue().model());
        // Stage-2 fix contract: the provider must receive an inlined base64
        // data url, NOT the presigned storage-host url — cloud providers fetch
        // images server-side and cannot reach localhost S3 (Dashscope 400,
        // live-proven ); VisualLocateServiceImpl data-url precedent.
        assertTrue(user.parts() != null && user.parts().stream()
                .anyMatch(p -> p.imageUrl() != null
                        && p.imageUrl().url().startsWith("data:image/png;base64,")),
                "inlined data url expected, got: " + describeParts(user));
        Integer logged = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_call_log WHERE project_id = ?",
                Integer.class, PROJECT);
        assertEquals(1, logged);
    }

    @Test
    void screenshotDownloadFailureDegradesWithoutModelCallAndWithoutBreakingTheTurn() {
        switches("1", "1");
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("glm-vision-test");
        when(coreGateway.taskScreenshots(55L)).thenReturn(List.of(new TaskScreenshotView(
                55L, "proj/7/screenshot/fail-3.png", "http://s3.local/presigned")));
        when(bytesFetcher.fetch(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("存储服务返回状态码 403（预签名地址可能已过期，有效期 15 分钟）"));

        FailureScreenshotEvidence evidence = resolver.resolve(PROJECT, resolvedDiagnosis());

        assertTrue(evidence.absent(), evidence.absentReason());
        assertTrue(evidence.absentReason().contains("403"), evidence.absentReason());
        assertFalse(evidence.attached(), "no half-baked scene on download failure");
        verifyNoInteractions(llmFallbackChain);
        Integer logged = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_call_log WHERE project_id = ?",
                Integer.class, PROJECT);
        assertEquals(0, logged);
    }

    private static String describeParts(ChatMessage msg) {
        return msg.parts() == null ? "null" : msg.parts().toString();
    }

    @Test
    void providerFailureDegradesInsteadOfBreakingTheTextOnlyAnswer() {
        switches("1", "1");
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("glm-vision-test");
        when(coreGateway.taskScreenshots(55L)).thenReturn(List.of(new TaskScreenshotView(
                55L, "proj/7/screenshot/fail-3.png", "http://s3.local/presigned")));
        when(llmFallbackChain.chatHandle(any(), eq(PROJECT), isNull()))
                .thenThrow(new IllegalStateException("provider down"));

        FailureScreenshotEvidence evidence = resolver.resolve(PROJECT, resolvedDiagnosis());

        assertTrue(evidence.absent());
        assertFalse(evidence.attached(), "no half-baked scene on failure");
        assertEquals("", evidence.sceneBlock());
    }

    @Test
    void unresolvedDiagnosisShortCircuitsToNoneWithoutTouchingAnyDoor() {
        FailureScreenshotEvidence evidence = resolver.resolve(PROJECT, FailureDiagnosis.NONE);

        assertEquals(FailureScreenshotEvidence.NONE, evidence);
        verifyNoInteractions(llmFallbackChain);
    }

    // ---- BF batch half-slice B1b: vision-less OCR fallback branch ----

    @Test
    void blankVisionModelWithOcrFallbackDenseTranscribesTheSceneWithMarkersAndGuidance() {
        switches("1", "1");
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("");
        when(coreGateway.getSysConfig("ai.ocr.fallback.enabled")).thenReturn("true");
        when(coreGateway.getSysConfig("ai.ocr.fallback-line-min")).thenReturn("4");
        when(coreGateway.taskScreenshots(55L)).thenReturn(List.of(new TaskScreenshotView(
                55L, "proj/7/screenshot/fail-3.png", "http://s3.local/presigned-ocr")));
        byte[] image = new byte[]{(byte) 0x89, 'P', 'N', 'G', 9};
        when(bytesFetcher.fetch(eq("http://s3.local/presigned-ocr"), anyInt()))
                .thenReturn(image);
        when(fallbackReader.read(eq(image), eq(4))).thenReturn(OcrFallbackOutcome.dense(
                OcrTextFallbackReader.MARKER_OPEN + "\n报错 | 服务超时\n按钮 | 置灰\n"
                        + OcrTextFallbackReader.MARKER_CLOSE, 8));

        FailureScreenshotEvidence evidence = resolver.resolve(PROJECT, resolvedDiagnosis());

        assertTrue(evidence.attached(), evidence.absentReason());
        String block = evidence.sceneBlock();
        assertTrue(block.contains(OcrTextFallbackReader.MARKER_OPEN)
                        && block.contains(OcrTextFallbackReader.MARKER_CLOSE),
                "scene transcription must ride the outbound prompt inside the pair markers");
        assertTrue(block.indexOf(OcrTextFallbackReader.MARKER_CLOSE)
                        > block.indexOf(OcrTextFallbackReader.MARKER_OPEN), block);
        assertTrue(block.contains("系统设置") && block.contains("ai.vision.model"), block);
        assertTrue(block.contains("非图像理解"), block);
        assertEquals("proj/7/screenshot/fail-3.png", evidence.objectKey());
        verify(llmFallbackChain, never()).chatHandle(any(), anyLong(), any());
        Integer logged = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_call_log WHERE project_id = ?",
                Integer.class, PROJECT);
        assertEquals(0, logged);
    }

    @Test
    void blankVisionModelWithOcrFallbackEngineFailureNamesTheReasonAndNeverPretends() {
        switches("1", "1");
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("");
        when(coreGateway.getSysConfig("ai.ocr.fallback.enabled")).thenReturn("true");
        when(coreGateway.taskScreenshots(55L)).thenReturn(List.of(new TaskScreenshotView(
                55L, "proj/7/screenshot/fail-3.png", "http://s3.local/presigned-ocr")));
        when(bytesFetcher.fetch(anyString(), anyInt())).thenReturn(new byte[]{1, 2});
        when(fallbackReader.read(any(), anyInt()))
                .thenReturn(OcrFallbackOutcome.failed("OCR 引擎不可用：worker 未注册"));

        FailureScreenshotEvidence evidence = resolver.resolve(PROJECT, resolvedDiagnosis());

        assertTrue(evidence.absent(), evidence.absentReason());
        assertTrue(evidence.absentReason().contains("OCR 引擎不可用：worker 未注册"),
                evidence.absentReason());
        assertFalse(evidence.sceneBlock().contains(OcrTextFallbackReader.MARKER_OPEN),
                "a failed read must not emit a transcription block");
        verify(llmFallbackChain, never()).chatHandle(any(), anyLong(), any());
    }

    @Test
    void blankVisionModelWithOcrFallbackOffKeepsTheLegacyRefusalAndReadsNoBytes() {
        switches("1", "1");
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn(" ");
        when(coreGateway.getSysConfig("ai.ocr.fallback.enabled")).thenReturn("false");

        FailureScreenshotEvidence evidence = resolver.resolve(PROJECT, resolvedDiagnosis());

        assertTrue(evidence.absent(), evidence.absentReason());
        assertTrue(evidence.absentReason().contains("不会改用文本模型假装视觉"),
                evidence.absentReason());
        verifyNoInteractions(fallbackReader);
        verify(coreGateway, never()).taskScreenshots(anyLong());
    }

    @Test
    void visionPathSceneCarriesNoOcrProvenanceMarkers() {
        switches("1", "1");
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("glm-vision-test");
        when(coreGateway.taskScreenshots(55L)).thenReturn(List.of(new TaskScreenshotView(
                55L, "proj/7/screenshot/fail-3.png", "http://s3.local/presigned")));
        when(bytesFetcher.fetch(eq("http://s3.local/presigned"), anyInt()))
                .thenReturn(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        when(llmFallbackChain.chatHandle(any(), eq(PROJECT), isNull()))
                .thenReturn(new LlmChatHandle(new LlmChatResult(
                        "页面停留在订单列表，弹窗提示“服务超时”", "test-provider",
                        "glm-vision-test", 300, 40, 800L),
                        new LlmEndpointConfig("test-provider", "glm-vision-test", "k",
                                "http://x", "config")));

        FailureScreenshotEvidence evidence = resolver.resolve(PROJECT, resolvedDiagnosis());

        assertTrue(evidence.attached(), evidence.absentReason());
        assertFalse(evidence.sceneBlock().contains(OcrTextFallbackReader.MARKER_OPEN),
                "the vision leg must never smuggle OCR provenance markers (pair fixture)");
        verifyNoInteractions(fallbackReader);
    }
}
