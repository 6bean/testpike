package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.ContextBudgetPlanner;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * segment B — chapter bucketing of the FINAL extraction call.
 *
 * <p>Incident grounding: document 8491 (642 chunks) burned the 300s provider
 * timeout inside ONE monolithic final-extract request. The fix splits the
 * final-extract input into budget-bounded buckets through the SAME greedy
 * loop and overlap function as the grouping plane, retries a malformed
 * bucket alone (a bad bucket must never re-bill the good ones) and caps
 * cross-bucket concurrency at three.
 *
 * <p>L2 scope, mocks limited to infrastructure (CoreGateway / LlmFallbackChain
 * / ProjectLlmRouter) per the testing iron law; the real provider quality
 * plane stays on L4. The routed endpoint here carries promptLimitTokens=null,
 * so no catalog row is needed to reach the ModelAdaptiveBudget fallback.
 */
class DocExtractFinalBucketingTest {

    private static final long DOC_ID = 91L;
    private static final long PROJECT_ID = 3L;
    private static final String TOKEN = "bucketing-gen-token-001";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG);
    }

    private static AiDocExtractMessage message() {
        return new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN);
    }

    /** 60-char content block carrying a unique marker in the title slot. */
    private static Map<String, Object> chunk(int seq, String marker) {
        return Map.of("seq", seq, "chapter", "章节" + marker,
                "content", marker + "-" + "字".repeat(54));
    }

    private void stubChunks(List<Map<String, Object>> chunks) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(chunks);
    }

    /** Valid one-item extraction payload. */
    private static String okJson(String title) {
        return "{\"requirements\":[{\"title\":\"" + title + "\",\"acceptanceCriteria\":\"通过\","
                + "\"chapter\":\"章节\",\"confidence\":0.9}]}";
    }

    /** Route every final-extract call by the chunk marker visible in its prompt. */
    private void stubByMarker(java.util.function.Function<String, String> responder) {
        when(llmChain.chat(any(ChatCompletionRequest.class), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean(), any())).thenAnswer(inv -> {
            ChatCompletionRequest req = inv.getArgument(0);
            String user = req.messages().get(1).content();
            return new LlmChatResult(responder.apply(user), "openai", "routed-model", 100, 50, 20L);
        });
    }

    /** The hot key under test, written as a literal so this class is the
     *  behavior RED before the constant exists on any production face. */
    private static String bucketKey() {
        return "ai.doc.final-bucket-max-tokens";
    }

    /**
     * TC-XQ-08(a) split plane: an over-bucket input becomes one call per
     * bucket and the merged write-back keeps every bucket's items in order.
     */
    @Test
    void finalInputOverBucketSplitsIntoBoundedCallsAndMergesInOrder() {
        DocExtractServiceImpl svc = newService();
        stubChunksOn(svc, chunk(1, "ALPHA"), chunk(2, "BRAVO"), chunk(3, "CHARLIE"));
        when(coreGateway.getSysConfig(bucketKey())).thenReturn("100");
when(coreGateway.getSysConfig(DocExtractServiceImpl.KEY_ANCHOR_REQUIRED_ENABLED)).thenReturn("false"); // recalibration: fixture excerpts are not verbatim-citable, this test pins a non-anchor plane; the landing gate stands off here (gate face is pinned by DocExtractAnchorClosedLoopTest).
        stubByMarker(user -> user.contains("ALPHA") ? okJson("T1")
                : user.contains("BRAVO") ? okJson("T2") : okJson("T3"));

        svc.extract(message());

        ArgumentCaptor<ChatCompletionRequest> calls =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, times(3)).chat(calls.capture(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> items = ArgumentCaptor.forClass(List.class);
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), items.capture(), eq(TOKEN));
        assertEquals(List.of("T1", "T2", "T3"),
                items.getValue().stream().map(m -> String.valueOf(m.get("title"))).toList());
        verify(coreGateway, never()).markExtractionFailed(eq(DOC_ID), any(), any());
    }

    /**
     * TC-XQ-08(b) bucket-level retry: a malformed bucket re-rolls ONLY that
     * bucket with the format feedback; the good bucket is billed once and
     * never sees the retry conversation.
     */
    @Test
    void malformedBucketReRollsOnlyThatBucketGoodBucketBilledOnce() {
        DocExtractServiceImpl svc = newService();
        stubChunksOn(svc, chunk(1, "ALPHA"), chunk(2, "BRAVO"));
        when(coreGateway.getSysConfig(bucketKey())).thenReturn("100");
        AtomicInteger alpha = new AtomicInteger();
        AtomicInteger bravo = new AtomicInteger();
        stubByMarker(user -> {
            if (user.contains("ALPHA")) {
                return alpha.getAndIncrement() == 0 ? "这不是JSON" : okJson("T1");
            }
            bravo.incrementAndGet();
            return okJson("T2");
        });

        svc.extract(message());

        assertEquals(2, alpha.get(), "bad bucket re-rolls once (its own retry budget)");
        assertEquals(1, bravo.get(), "good bucket must never be re-billed by another bucket's failure");
        verify(llmChain, times(3)).chat(any(ChatCompletionRequest.class), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean(), any());
    }

    /**
     * TC-XQ-08(c) zero-drift escape: key "0" keeps today's single monolithic
     * call even when the input exceeds the bucket window by design.
     */
    @Test
    void bucketKeyZeroKeepsLegacySingleCall() {
        DocExtractServiceImpl svc = newService();
        stubChunksOn(svc, chunk(1, "ALPHA"), chunk(2, "BRAVO"), chunk(3, "CHARLIE"));
        when(coreGateway.getSysConfig(bucketKey())).thenReturn("0");
        stubByMarker(user -> okJson("T1"));

        svc.extract(message());

        ArgumentCaptor<ChatCompletionRequest> calls =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, times(1)).chat(calls.capture(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean(), any());
        String user = calls.getValue().messages().get(1).content();
        assertTrue(user.contains("ALPHA") && user.contains("BRAVO") && user.contains("CHARLIE"),
                "the disabled face sends the whole package in one prompt, byte-identical plane");
    }

    /**
     * TC-XQ-08(d) fail-closed merge: a bucket that stays malformed after its
     * own retries aborts the whole extraction with the format error — partial
     * item sets are never written back silently.
     */
    @Test
    void persistentlyMalformedBucketAbortsWriteBackFailClosed() {
        DocExtractServiceImpl svc = newService();
        stubChunksOn(svc, chunk(1, "ALPHA"), chunk(2, "BRAVO"));
        when(coreGateway.getSysConfig(bucketKey())).thenReturn("100");
        stubByMarker(user -> user.contains("ALPHA") ? "坏输出" : okJson("T2"));

        assertThrows(ServiceException.class, () -> svc.extract(message()));

        verify(coreGateway, never()).writeExtractionResult(eq(DOC_ID), any(), any());
        verify(coreGateway).markExtractionFailed(eq(DOC_ID), anyString(), eq(TOKEN));
        verify(llmChain, times(4)).chat(any(ChatCompletionRequest.class), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean(), any());
        verify(llmChain, times(1)).chat(org.mockito.ArgumentMatchers.argThat(
                r -> r.messages().get(1).content().contains("BRAVO")), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean(), any());
    }

    /**
     * TC-XQ-08(e) concurrency ceiling: six buckets must never drive more than
     * three overlapping provider calls (self-adjudication ③: capped headroom,
     * never unbounded fan-out). The fake holds each call briefly so a wider
     * pool would observably breach the cap.
     */
    @Test
    void bucketPlaneParallelismStaysWithinThree() {
        DocExtractServiceImpl svc = newService();
        stubChunksOn(svc, chunk(1, "B1"), chunk(2, "B2"), chunk(3, "B3"),
                chunk(4, "B4"), chunk(5, "B5"), chunk(6, "B6"));
        when(coreGateway.getSysConfig(bucketKey())).thenReturn("100");
        AtomicInteger live = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        stubByMarker(user -> {
            int now = live.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(120);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            live.decrementAndGet();
            return okJson("T" + user.indexOf('B'));
        });

        svc.extract(message());

        assertTrue(peak.get() <= 3, "cross-bucket concurrency must stay capped at 3, saw " + peak.get());
        verify(llmChain, times(6)).chat(any(ChatCompletionRequest.class), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean(), any());
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), any(), eq(TOKEN));
    }

    // ── harness: a fresh service per test sharing the same mocks ────────

    /** Catalog-row face with an explicit prompt limit (for the automatic ceiling). */
    private static LlmEndpointConfig endpoint(int promptLimit) {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG, promptLimit);
    }

    /** ~3200-char content block, marker first so prompt routing stays unique. */
    private static Map<String, Object> bigChunk(int seq, String marker) {
        return Map.of("seq", seq, "chapter", "章节" + marker,
                "content", marker + "-" + "字".repeat(3193));
    }

    /**
     * TC-XQ-08(f) automatic face: with no stored ceiling the plane splits at
     * effective budget x 0.6 headroom, so a package that still fits the
     * planner budget goes out as bounded buckets instead of one monolithic
     * call (this is what keeps document 8491-class payloads inside the
     * provider window without any operator action).
     */
    @Test
    void blankCeilingFallsToAutomaticHeadroomAndStillSplits() {
        DocExtractServiceImpl svc = newService();
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint(13000));
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                bigChunk(1, "ALPHA"), bigChunk(2, "BRAVO")));
        // Ceiling key stays unstubbed: the mock answers null = automatic face.
when(coreGateway.getSysConfig(DocExtractServiceImpl.KEY_ANCHOR_REQUIRED_ENABLED)).thenReturn("false"); // recalibration: fixture excerpts are not verbatim-citable, this test pins a non-anchor plane; the landing gate stands off here (gate face is pinned by DocExtractAnchorClosedLoopTest).
        stubByMarker(user -> user.contains("ALPHA") ? okJson("T1") : okJson("T2"));

        svc.extract(message());

        verify(llmChain, times(2)).chat(any(ChatCompletionRequest.class), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> items = ArgumentCaptor.forClass(List.class);
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), items.capture(), eq(TOKEN));
        assertEquals(List.of("T1", "T2"),
                items.getValue().stream().map(m -> String.valueOf(m.get("title"))).toList());
    }

    /**
 * so a bucket must never weld two chapters together while each chapter
 * alone fits under the ceiling. Budget-only greedy packs A1+A2+B1 into
 * one call (3x29 chars fits the 93-char window for a 100-token ceiling)
 * — the aligned face seals at the chapter change instead: [A1 A2][B1 B2].
 * Overlap tail stays empty (0.10 x 93 < one chunk), so both faces answer
 * with two calls and the contract is judged on bucket MEMBERSHIP.
 */
    @Test
    void finalBucketsSealAtChapterChangeNeverWeldingTwoChapters() {
        DocExtractServiceImpl svc = newService();
        java.util.function.BiFunction<Integer, String, Map<String, Object>> block =
                (seq, marker) -> Map.of("seq", seq, "chapter",
                        seq <= 2 ? "第一章" : "第二章",
                        "content", marker + "字".repeat(24));
        List<Map<String, Object>> chunks = List.of(
                block.apply(1, "ALPHA"), block.apply(2, "BRAVO"),
                block.apply(3, "CHARLIE"), block.apply(4, "DELTA"));
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(chunks);
        when(coreGateway.getSysConfig(bucketKey())).thenReturn("100");
        when(coreGateway.getSysConfig(DocExtractServiceImpl.KEY_ANCHOR_REQUIRED_ENABLED))
                .thenReturn("false"); // fixture excerpts are not verbatim-citable
        stubByMarker(user -> user.contains("ALPHA") ? okJson("T1")
                : user.contains("DELTA") ? okJson("T2") : okJson("X"));

        svc.extract(message());

        ArgumentCaptor<ChatCompletionRequest> calls =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, times(2)).chat(calls.capture(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean(), any());
        for (ChatCompletionRequest req : calls.getAllValues()) {
            String user = req.messages().get(1).content();
            boolean hasFirstChapter = user.contains("ALPHA") || user.contains("BRAVO");
            boolean hasSecondChapter = user.contains("CHARLIE") || user.contains("DELTA");
            assertEquals(false, hasFirstChapter && hasSecondChapter,
                    "final bucket welded two chapters into one call");
        }
        verify(llmChain, times(1)).chat(org.mockito.ArgumentMatchers.argThat(
                req -> req.messages().get(1).content().contains("ALPHA")
                        && req.messages().get(1).content().contains("BRAVO")
                        && !req.messages().get(1).content().contains("CHARLIE")),
                eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean(), any());
    }

    private DocExtractServiceImpl newService() {
        return new DocExtractServiceImpl(coreGateway, llmChain, llmRouter, new ObjectMapper());
    }

    private void stubChunksOn(DocExtractServiceImpl svc, Map<String, Object>... chunks) {
        stubChunks(List.of(chunks));
    }
}
