package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.aichat.domain.component.ChatUserImageEvidence;
import com.sixbean.testpike.aiservice.aichat.domain.component.FailureScreenshotBytesFetcher;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.testsupport.AiCallLogTestCleanup;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.pipeline.RedactGate;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrFallbackOutcome;
import com.sixbean.testpike.aiservice.ocr.domain.service.OcrTextFallbackReader;
import com.sixbean.testpike.common.aichat.ChatImageAttachment;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Dialog user-image vision round-trip contract
 * ladder. Real DB posture identical to {@code FailureScreenshotResolverTest}:
 * the call-log rows land for real, only the core door, the provider chain and
 * the bytes fetcher are isolated (L2 infrastructure isolation — the real
 * wiring lands on the E2E ladder).
 * <p>Assertions:
 * ① no attachments = NONE before any gate is read (zero config reads, zero
 * door calls — a photoless turn keeps its exact old shape);
 * ② sub-switch off (ai.vision.chat-user-image != 1) = silent NONE, the
 * canonical list is never even walked through the door;
 * ③ sub on + master gate off = ONE honest absent reason naming both literal
 * keys and where to open them (R32 guidance contract);
 * ④ both on + vision model blank = absent naming ai.vision.model — the text
 * router is never borrowed to pretend vision;
 * ⑤ happy path (2 images) = per-image vision call with an inlined base64
 * data url, both observations ride the block, one real tp_ai_call_log row
 * per model round-trip;
 * ⑥ every image fails at the door/bytes leg = absent naming the reason,
 * zero model calls, zero call-log rows (never a half-material answer);
 * ⑦ mixed outcome = attached with the failed image named inside the block
 * (the good observations still ride, the bad leg is never hidden).
 */
@SpringBootTest
class ChatUserImageResolverTest {

    private static final long PROJECT_BASE = 989_140_000L;
    private static final long PROJECT = PROJECT_BASE + 1L;
    private static final long SESSION = 4711L;

    @Autowired
    private ChatUserImageResolver resolver;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CoreGateway coreGateway;

    @MockitoBean
    private LlmFallbackChain llmFallbackChain;

    @MockitoBean
    private FailureScreenshotBytesFetcher bytesFetcher;

    @MockitoBean
    private com.sixbean.testpike.aiservice.devtoolspanel.domain.service
            .ScreenshotLoginStateStager panelStager;

    @MockitoBean
    private OcrTextFallbackReader fallbackReader;

    @AfterEach
    void cleanup() {
        AiCallLogTestCleanup.purgeProjectRange(jdbcTemplate, PROJECT_BASE);
    }

    private static List<ChatImageAttachment> twoImages() {
        return List.of(
                new ChatImageAttachment(PROJECT + "/chat-image/202610/ab12-崩溃首页.png", "崩溃首页.png"),
                new ChatImageAttachment(PROJECT + "/chat-image/202610/cd34-报错弹窗.jpg", "报错弹窗.jpg"));
    }

    private void switches(String master, String sub) {
        when(coreGateway.getSysConfig("ai.vision.enabled")).thenReturn(master);
        when(coreGateway.getSysConfig("ai.vision.chat-user-image")).thenReturn(sub);
    }

    private void modelSet() {
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("glm-vision-test");
    }

    private void stubHappyVision(String observation) {
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), eq(PROJECT), isNull()))
                .thenReturn(new LlmChatHandle(new LlmChatResult(
                        observation, "test-provider", "glm-vision-test", 300, 40, 800L),
                        new LlmEndpointConfig("test-provider", "glm-vision-test", "k",
                                "http://x", "config")));
    }

    @Test
    void photolessTurnIsNoneBeforeAnyGateIsReadWithZeroDoorCalls() {
        ChatUserImageEvidence evidence = resolver.resolve(PROJECT, SESSION, List.of());

        assertEquals(ChatUserImageEvidence.NONE, evidence);
        assertFalse(evidence.attached());
        assertFalse(evidence.absent());
        ChatUserImageEvidence nullList = resolver.resolve(PROJECT, SESSION, null);
        assertEquals(ChatUserImageEvidence.NONE, nullList);
        verifyNoInteractions(coreGateway, bytesFetcher, llmFallbackChain);
    }

    @Test
    void subSwitchOffIsSilentNoneWithZeroBytesReadsAndZeroModelCalls() {
        switches("1", "0");

        ChatUserImageEvidence evidence = resolver.resolve(PROJECT, SESSION, twoImages());

        assertEquals(ChatUserImageEvidence.NONE, evidence);
        verifyNoInteractions(bytesFetcher, llmFallbackChain);
        verify(coreGateway, never()).chatImageUrl(anyLong(), anyString());
    }

    @Test
    void masterGateOffDegradesHonestlyNamingBothLiteralKeysAndTheSettingsDoor() {
        switches("0", "1");

        ChatUserImageEvidence evidence = resolver.resolve(PROJECT, SESSION, twoImages());

        assertTrue(evidence.absent(), evidence.absentReason());
        assertTrue(evidence.absentReason().contains("ai.vision.enabled"), evidence.absentReason());
        assertTrue(evidence.absentReason().contains("ai.vision.chat-user-image"),
                evidence.absentReason());
        assertTrue(evidence.absentReason().contains("系统管理 → 系统配置"),
                evidence.absentReason());
        verifyNoInteractions(bytesFetcher, llmFallbackChain);
    }

    @Test
    void blankVisionModelDegradesNamingModelKeyInsteadOfPretendingWithTextModel() {
        switches("1", "1");
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn(" ");

        ChatUserImageEvidence evidence = resolver.resolve(PROJECT, SESSION, twoImages());

        assertTrue(evidence.absent(), evidence.absentReason());
        assertTrue(evidence.absentReason().contains("ai.vision.model"), evidence.absentReason());
        verifyNoInteractions(bytesFetcher);
        // Zero-vision posture asserted at the call entry, not as a class-wide
        // "no interactions" on the provider chain: lazy Spring wiring touches
        // that bean once from DocExtractServiceImpl's setter during context
        // bring-up (ordering artifact of targeted -Dtest runs, D2b).
        verify(llmFallbackChain, never()).chatHandle(any(), anyLong(), any());
    }

    @Test
    void happyPathReadsBothImagesWithInlinedDataUrlsAndLandsRealCallLogRows() {
        switches("1", "1");
        modelSet();
        when(coreGateway.chatImageUrl(PROJECT,
                PROJECT + "/chat-image/202610/ab12-崩溃首页.png"))
                .thenReturn("http://storage.local/presigned-a");
        when(coreGateway.chatImageUrl(PROJECT,
                PROJECT + "/chat-image/202610/cd34-报错弹窗.jpg"))
                .thenReturn("http://storage.local/presigned-b");
        when(bytesFetcher.fetch(eq("http://storage.local/presigned-a"), anyInt()))
                .thenReturn(new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
        when(bytesFetcher.fetch(eq("http://storage.local/presigned-b"), anyInt()))
                .thenReturn(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 4, 5});
        stubHappyVision("页面顶部出现红色报错横幅，提交按钮不可点击");

        ChatUserImageEvidence evidence = resolver.resolve(PROJECT, SESSION, twoImages());

        assertTrue(evidence.attached(), "observation block expected");
        assertTrue(evidence.observationBlock().contains("崩溃首页.png"), evidence.observationBlock());
        assertTrue(evidence.observationBlock().contains("报错弹窗.jpg"), evidence.observationBlock());
        assertTrue(evidence.observationBlock().contains("红色报错横幅"), evidence.observationBlock());
        // seam: every image line must disclose its booked session handle,
        // otherwise the read-only dialog OCR tools can only ever receive the
        // display name and the fail-closed ownership door refuses correctly.
        assertTrue(evidence.observationBlock().contains(
                "〔会话句柄 " + PROJECT + "/chat-image/202610/ab12-崩溃首页.png〕"),
                evidence.observationBlock());
        assertTrue(evidence.observationBlock().contains(
                "〔会话句柄 " + PROJECT + "/chat-image/202610/cd34-报错弹窗.jpg〕"),
                evidence.observationBlock());
        assertEquals(2, evidence.readCount());
        ArgumentCaptor<ChatCompletionRequest> request =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain, times(2))
                .chatHandle(request.capture(), eq(PROJECT), isNull());
        // Stage-2 fix contract inherited from the failure-screenshot point:
        // the provider receives an inlined base64 data url, never the
        // storage-host presigned url (cloud providers cannot reach it —
        // Dashscope 400, live-proven ).
        List<ChatCompletionRequest> calls = request.getAllValues();
        assertTrue(dataUrlOf(calls.get(0)).startsWith("data:image/png;base64,"),
                "png mime expected from the file extension");
        assertTrue(dataUrlOf(calls.get(1)).startsWith("data:image/jpeg;base64,"),
                "jpeg mime expected from the file extension");
        Integer logged = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_call_log WHERE project_id = ?",
                Integer.class, PROJECT);
        assertEquals(2, logged, "one call-log row per model round-trip");
    }

    @Test
    void everyImageFailingAtTheDoorDegradesWithZeroModelCallsAndZeroLogRows() {
        switches("1", "1");
        modelSet();
        when(coreGateway.chatImageUrl(anyLong(), anyString()))
                .thenThrow(new IllegalStateException("core 读门不可达：连接超时"));

        ChatUserImageEvidence evidence = resolver.resolve(PROJECT, SESSION, twoImages());

        assertTrue(evidence.absent(), evidence.absentReason());
        assertTrue(evidence.absentReason().contains("连接超时"), evidence.absentReason());
        assertFalse(evidence.attached(), "no half-baked observations on a dead door");
        verifyNoInteractions(bytesFetcher, llmFallbackChain);
        Integer logged = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_call_log WHERE project_id = ?",
                Integer.class, PROJECT);
        assertEquals(0, logged);
    }

    @Test
    void mixedOutcomeAttachesTheGoodObservationsAndNamesTheFailedImage() {
        switches("1", "1");
        modelSet();
        when(coreGateway.chatImageUrl(PROJECT,
                PROJECT + "/chat-image/202610/ab12-崩溃首页.png"))
                .thenReturn("http://storage.local/presigned-a");
        when(coreGateway.chatImageUrl(PROJECT,
                PROJECT + "/chat-image/202610/cd34-报错弹窗.jpg"))
                .thenReturn("http://storage.local/presigned-b");
        when(bytesFetcher.fetch(eq("http://storage.local/presigned-a"), anyInt()))
                .thenReturn(new byte[]{(byte) 0x89, 'P', 'N', 'G', 1});
        when(bytesFetcher.fetch(eq("http://storage.local/presigned-b"), anyInt()))
                .thenThrow(new IllegalStateException("storage service returned status code 403 (presigned URL may have expired)"));
        stubHappyVision("订单列表页空白区域超过半屏");

        ChatUserImageEvidence evidence = resolver.resolve(PROJECT, SESSION, twoImages());

        assertTrue(evidence.attached(), evidence.observationBlock());
        assertTrue(evidence.observationBlock().contains("订单列表页空白区域"),
                evidence.observationBlock());
        assertTrue(evidence.observationBlock().contains("报错弹窗.jpg"),
                "the failed image must be named, never silently dropped");
        assertTrue(evidence.observationBlock().contains("403"), evidence.observationBlock());
        assertEquals(1, evidence.readCount());
    }

    @Test
    void stagedPanelSummaryRidesAfterTheObservationsAndCarriesNoValues() {
        switches("1", "1");
        modelSet();
        when(coreGateway.chatImageUrl(anyLong(), anyString()))
                .thenReturn("http://storage.local/presigned-a");
        when(bytesFetcher.fetch(anyString(), anyInt())).thenReturn(new byte[]{(byte) 0x89, 'P'});
        stubHappyVision("这是 DevTools Application 面板截图");
        when(panelStager.stageFromScreenshot(eq(SESSION), any()))
                .thenReturn(new com.sixbean.testpike.aiservice.devtoolspanel.api.dto
                        .PanelStageOutcome("550e8400-e29b-41d4-a716-446655440000",
                        List.of("cookie SESSION @ .example.com（已标红）"), 1, 2));

        ChatUserImageEvidence evidence = resolver.resolve(PROJECT, SESSION, twoImages());

        assertTrue(evidence.attached(), evidence.absentReason());
        String block = evidence.observationBlock();
        // The staging token is a slot pointer, never a credential: the row is
        // rendered in a declarative form so the pre-send redaction gate (whose
        // assignment word-shape would mask "token=<value>") leaves it intact
        // and the model can thread the exact value back into register.
        assertTrue(block.contains("【DevTools 面板解析摘要】暂存凭证 "
                + "550e8400-e29b-41d4-a716-446655440000（stagingToken 填此值）"), block);
        assertEquals(block, RedactGate.redactOutbound("1", null, block),
                "the panel summary must pass the redaction gate untouched");
        assertTrue(block.contains("cookie SESSION @ .example.com（已标红）"), block);
        assertTrue(block.contains("另有 2 行取值疑似被列宽截断"), block);
        // The summary is appended AFTER the observation block (plan contract).
        assertTrue(block.indexOf("DevTools 面板解析摘要") > block.lastIndexOf("DevTools Application 面板截图"),
                block);
        // The staged rows were produced for every image whose bytes came
        // through — two attachments, one session anchor threaded down.
        verify(panelStager, times(2)).stageFromScreenshot(eq(SESSION), any());
    }

    private static String dataUrlOf(ChatCompletionRequest req) {
        ChatMessage user = req.messages().stream()
                .filter(m -> "user".equals(m.role())).findFirst().orElseThrow();
        return user.parts().stream()
                .filter(p -> p.imageUrl() != null)
                .map(p -> p.imageUrl().url())
                .findFirst().orElse("");
    }

    // ---- BF batch half-slice B1b: vision-less OCR fallback branch ----

    private void visionLessFallbackOn() {
        when(coreGateway.getSysConfig("ai.ocr.fallback.enabled")).thenReturn("true");
    }

    @Test
    void blankVisionModelWithOcrFallbackDenseTranscribesWithProvenanceMarkersAndGuidance() {
        switches("1", "1");
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn(" ");
        visionLessFallbackOn();
        when(coreGateway.getSysConfig("ai.ocr.fallback-line-min")).thenReturn("5");
        when(coreGateway.chatImageUrl(eq(PROJECT), anyString()))
                .thenReturn("http://storage.local/presigned-ocr");
        byte[] image = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1};
        when(bytesFetcher.fetch(eq("http://storage.local/presigned-ocr"), anyInt()))
                .thenReturn(image);
        when(fallbackReader.read(eq(image), eq(5))).thenReturn(OcrFallbackOutcome.dense(
                OcrTextFallbackReader.MARKER_OPEN + "\n登录 | 用户名\n输入框 | admin\n"
                        + OcrTextFallbackReader.MARKER_CLOSE, 6));

        ChatUserImageEvidence evidence = resolver.resolve(PROJECT, SESSION, List.of(
                new ChatImageAttachment(PROJECT + "/chat-image/202610/ab12-登录页.png", "登录页.png")));

        assertTrue(evidence.attached(), evidence.absentReason());
        String block = evidence.observationBlock();
        assertTrue(block.contains(OcrTextFallbackReader.MARKER_OPEN)
                        && block.contains(OcrTextFallbackReader.MARKER_CLOSE),
                "OCR transcription must ride the outbound prompt inside the pair markers");
        assertTrue(block.indexOf(OcrTextFallbackReader.MARKER_CLOSE)
                        > block.indexOf(OcrTextFallbackReader.MARKER_OPEN),
                "markers must stay a well-formed pair after assembly");
        // R32 dual-factor guidance: settings path literal + the model key literal.
        assertTrue(block.contains("系统设置") && block.contains("ai.vision.model"), block);
        assertTrue(block.contains("非图像理解"), block);
        // Zero vision round-trips: the fallback leg never burns a model call.
        verify(llmFallbackChain, never()).chatHandle(any(), anyLong(), any());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_call_log WHERE project_id = ?",
                Integer.class, PROJECT));
        // Hot-tunable floor: the value from sys config travels to the reader.
        verify(fallbackReader).read(eq(image), eq(5));
    }

    @Test
    void blankVisionModelWithOcrFallbackSparseRefusesHonestlyWithoutForceInterpreting() {
        switches("1", "1");
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("");
        visionLessFallbackOn();
        when(coreGateway.chatImageUrl(eq(PROJECT), anyString()))
                .thenReturn("http://storage.local/presigned-ocr");
        when(bytesFetcher.fetch(anyString(), anyInt())).thenReturn(new byte[]{1, 2});
        when(fallbackReader.read(any(), anyInt())).thenReturn(OcrFallbackOutcome.sparse(1));

        ChatUserImageEvidence evidence = resolver.resolve(PROJECT, SESSION, List.of(
                new ChatImageAttachment(PROJECT + "/chat-image/202610/cd34-海报.png", "海报.png")));

        assertTrue(evidence.absent(), evidence.absentReason());
        assertTrue(evidence.absentReason().contains("1 行"), evidence.absentReason());
        assertTrue(evidence.absentReason().contains("ai.vision.model"), evidence.absentReason());
        verify(llmFallbackChain, never()).chatHandle(any(), anyLong(), any());
    }

    @Test
    void blankVisionModelWithOcrFallbackOffKeepsTheLegacyRefusalAndReadsNoBytes() {
        switches("1", "1");
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn(" ");
        when(coreGateway.getSysConfig("ai.ocr.fallback.enabled")).thenReturn("false");

        ChatUserImageEvidence evidence = resolver.resolve(PROJECT, SESSION, twoImages());

        assertTrue(evidence.absent(), evidence.absentReason());
        assertTrue(evidence.absentReason().contains("不会改用文本模型假装视觉"),
                evidence.absentReason());
        verifyNoInteractions(fallbackReader, bytesFetcher);
    }

    @Test
    void visionPathWithGridAssistOffCarriesNoOcrProvenanceMarkers() {
        switches("1", "1");
        modelSet();
        when(coreGateway.getSysConfig("ai.ocr.grid-assist.enabled")).thenReturn("false");
        when(coreGateway.chatImageUrl(eq(PROJECT), anyString()))
                .thenReturn("http://storage.local/presigned-a");
        when(bytesFetcher.fetch(eq("http://storage.local/presigned-a"), anyInt()))
                .thenReturn(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        stubHappyVision("订单列表页空白区域超过半屏");

        ChatUserImageEvidence evidence = resolver.resolve(PROJECT, SESSION, List.of(
                new ChatImageAttachment(PROJECT + "/chat-image/202610/ab12-订单.png", "订单.png")));

        assertTrue(evidence.attached(), evidence.absentReason());
        assertFalse(evidence.observationBlock().contains(OcrTextFallbackReader.MARKER_OPEN),
                "assist switched off = the vision leg keeps the legacy marker-free shape");
        verifyNoInteractions(fallbackReader);
    }

    // ---- supplement item 2: the character post (OCR) on duty beside vision ----

    private void gridAssistOn() {
        when(coreGateway.getSysConfig("ai.ocr.grid-assist.enabled")).thenReturn("true");
    }

    private void assistBytes() {
        when(coreGateway.chatImageUrl(eq(PROJECT), anyString()))
                .thenReturn("http://storage.local/presigned-assist");
        when(bytesFetcher.fetch(eq("http://storage.local/presigned-assist"), anyInt()))
                .thenReturn(new byte[]{(byte) 0x89, 'P', 'N', 'G', 7});
    }

    private ChatUserImageEvidence resolveOneImage() {
        return resolver.resolve(PROJECT, SESSION, List.of(
                new ChatImageAttachment(PROJECT + "/chat-image/202610/ab12-订单.png", "订单.png")));
    }

    @Test
    void visionWithGridAssistOnAttachesOcrInitialValuesInsideProvenanceMarkers() {
        switches("1", "1");
        modelSet();
        gridAssistOn();
        assistBytes();
        stubHappyVision("订单列表页空白区域超过半屏，编号 REQ-2026-00012345 处理中");
        when(fallbackReader.read(any(byte[].class), eq(1))).thenReturn(OcrFallbackOutcome.dense(
                OcrTextFallbackReader.MARKER_OPEN + "\n订单编号 | REQ-2026-00012345\n状态 | 处理中\n"
                        + OcrTextFallbackReader.MARKER_CLOSE, 2));

        ChatUserImageEvidence evidence = resolveOneImage();

        assertTrue(evidence.attached(), evidence.absentReason());
        String block = evidence.observationBlock();
        assertTrue(block.contains("【字符岗 OCR 初值】")
                        && block.contains(OcrTextFallbackReader.MARKER_OPEN)
                        && block.contains(OcrTextFallbackReader.MARKER_CLOSE),
                "the deterministic baseline must ride the observation block inside the marker pair");
        assertTrue(block.contains("0 处分歧"), block);
        assertTrue(block.indexOf(OcrTextFallbackReader.MARKER_CLOSE)
                        > block.indexOf(OcrTextFallbackReader.MARKER_OPEN),
                "well-formed marker pair");
    }

    @Test
    void gridAssistNamesValueTokensTheVisionTranscriptionMissed() {
        switches("1", "1");
        modelSet();
        gridAssistOn();
        assistBytes();
        stubHappyVision("订单列表页空白区域超过半屏");
        when(fallbackReader.read(any(byte[].class), eq(1))).thenReturn(OcrFallbackOutcome.dense(
                "订单编号 | REQ-2026-00012345\n批次 | 202611140001", 2));

        ChatUserImageEvidence evidence = resolveOneImage();

        assertTrue(evidence.attached(), evidence.absentReason());
        String block = evidence.observationBlock();
        assertTrue(block.contains("REQ-2026-00012345") && block.contains("202611140001"),
                "disagreement rows name the value tokens verbatim (never silent)");
        assertTrue(block.contains("以 OCR 初值为基准"), block);
    }

    @Test
    void gridAssistBranchIsGatedByItsOwnKeyNotTheFallbackFamily() {
        switches("1", "1");
        modelSet();
        when(coreGateway.getSysConfig("ai.ocr.fallback.enabled")).thenReturn("false");
        gridAssistOn();
        assistBytes();
        stubHappyVision("订单列表页空白区域超过半屏");
        when(fallbackReader.read(any(byte[].class), eq(1))).thenReturn(OcrFallbackOutcome.dense(
                "订单编号 | REQ-2026-00012345", 1));

        ChatUserImageEvidence evidence = resolveOneImage();

        assertTrue(evidence.attached(), evidence.absentReason());
        assertTrue(evidence.observationBlock().contains("【字符岗 OCR 初值】"),
                "fallback-family keys must not gate the assist branch (orthogonality fixture)");
    }

    @Test
    void gridAssistSparseLegKeepsTheVisionRoundAttachedWithoutAnySection() {
        switches("1", "1");
        modelSet();
        gridAssistOn();
        assistBytes();
        stubHappyVision("订单列表页空白区域超过半屏");
        when(fallbackReader.read(any(byte[].class), eq(1))).thenReturn(OcrFallbackOutcome.sparse(0));

        ChatUserImageEvidence evidence = resolveOneImage();

        assertTrue(evidence.attached(), evidence.absentReason());
        assertFalse(evidence.observationBlock().contains(OcrTextFallbackReader.MARKER_OPEN),
                "a sparse assist leg adds no baseline section and never takes the vision round down");
    }
}
