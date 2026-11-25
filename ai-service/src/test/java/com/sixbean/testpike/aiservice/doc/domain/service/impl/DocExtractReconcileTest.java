package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.DocExtractLedgerStore;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AX batch (deep-mining duty generalised to the extraction
 * pipeline): after the first extraction round the orchestrator reconciles
 * every anchorable chunk against the items' evidence attribution. Chunks
 * nobody cited are gaps; each reconcile round re-extracts ONLY the gap
 * chunks (never the whole package again), stops early on a round with zero
 * newly-covered chunks, and is capped by the hot key
 * ai.doc.extract-reconcile-rounds (default 3, range 1-5, garbage falls back
 * to the default). The final step set — including honest pending gaps — is
 * persisted to the coverage ledger; a ledger door outage never fails the
 * extraction itself, and core's write-back keeps guarding the generation.
 * <p>L2 posture: CoreGateway / LlmFallbackChain / router / ledger store are
 * infrastructure mocks (same isolation face as DocExtractServiceImplTest);
 * real extraction quality lives at L4.
 */
class DocExtractReconcileTest {

    private static final long DOC_ID = 7L;
    private static final long PROJECT_ID = 3L;
    private static final String TOKEN = "gen-token-e2e-001";
    private static final String KEY_ROUNDS = "ai.doc.extract-reconcile-rounds";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final DocExtractLedgerStore ledgerStore = mock(DocExtractLedgerStore.class);
    private ListAppender<ILoggingEvent> logAppender;
    private final DocExtractServiceImpl service = DocExtractTestSupport.wiredService(
            coreGateway, llmChain, llmRouter, ledgerStore);

    @BeforeEach
    void attachReconcileLogs() {
        logAppender = DocExtractTestSupport.attachServiceLogCapture();
    }

    @AfterEach
    void detachReconcileLogs() {
        DocExtractTestSupport.detachServiceLogCapture(logAppender);
    }

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG, 2000);
    }

    private static AiDocExtractMessage message() {
        return new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN);
    }

    private static Map<String, Object> chunk(long id, int seq, String content) {
        return Map.of("id", id, "seq", seq, "chapter", "安全", "content", content);
    }

    private static final String LOGIN_TEXT = "用户连续输错密码五次后账号锁定十分钟";
    private static final String EXPORT_TEXT = "报表导出支持按月归档并保留最近二十四个月";
    /** One dense block with three enumerated entries (live 8905 shape). */
    private static final String DENSE_THREE =
            "1." + LOGIN_TEXT + "\n2." + EXPORT_TEXT + "\n3.水印不得关闭";
    /** A two-item draft settling the login-cited block's remaining shortfall. */
    private static final String DRAFT_EXPORT_AND_WATERMARK =
            "{\"requirements\":["
                    + "{\"title\":\"报表按月归档\",\"acceptanceCriteria\":\"保留24个月\","
                    + "\"chapter\":\"安全\",\"confidence\":0.9,\"evidence\":\"" + EXPORT_TEXT + "\"},"
                    + "{\"title\":\"水印不可关\",\"acceptanceCriteria\":\"水印常开\","
                    + "\"chapter\":\"安全\",\"confidence\":0.9,\"evidence\":\"水印不得关闭\"}]}";

    /** First round cites only the login chunk. */
    private static final String RESP_LOGIN_ONLY =
            "{\"requirements\":[{\"title\":\"登录失败锁定\",\"acceptanceCriteria\":\"五次锁定\","
                    + "\"chapter\":\"安全\",\"confidence\":0.9,\"evidence\":\"" + LOGIN_TEXT + "\"}]}";
    /** An item whose excerpt matches nothing the model was shown -> unattributed. */
    private static final String RESP_UNCITED =
            "{\"requirements\":[{\"title\":\"无关条目\",\"acceptanceCriteria\":\"x\","
                    + "\"chapter\":\"安全\",\"confidence\":0.4,\"evidence\":\"这段摘录不在任何分块里\"}]}";
    private static final String RESP_COVER_EXPORT =
            "{\"requirements\":[{\"title\":\"报表按月归档\",\"acceptanceCriteria\":\"保留24个月\","
                    + "\"chapter\":\"安全\",\"confidence\":0.9,\"evidence\":\"" + EXPORT_TEXT + "\"}]}";
    private static final String RESP_EMPTY = "{\"requirements\":[]}";

    private void stub(List<Map<String, Object>> chunks, String... responses) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(chunks);
        LlmChatResult last = null;
        org.mockito.stubbing.OngoingStubbing<LlmChatResult> chain = when(llmChain.chat(any(),
                eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean(), any()));
        for (String resp : responses) {
            last = new LlmChatResult(resp, "openai", "routed-model", 100, 50, 20L);
            chain = chain.thenReturn(last);
        }
    }

    @SuppressWarnings("unchecked")
    private String userPromptOfCall(int callIndex) {
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, times(callIndex)).chat(captor.capture(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean(), any());
        List<com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage> msgs =
                captor.getAllValues().get(callIndex - 1).messages();
        return msgs.get(1).content();
    }

    /**
 * the run lands stage rows as it proceeds — one at the
 * plane entry, one per finished final bucket, one per reconcile round —
 * through the same ledger door as the coverage steps (the preview page
 * reads them back as stageLedger).
 */
    @Test
    void stageLedger_recordsStartBucketAndReconcileRound_asTheRunProceeds() {
        stub(List.of(chunk(101L, 1, LOGIN_TEXT), chunk(102L, 2, EXPORT_TEXT)),
                RESP_LOGIN_ONLY, RESP_COVER_EXPORT);
        service.extract(message());
        ArgumentCaptor<String> codes = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> labels = ArgumentCaptor.forClass(String.class);
        verify(ledgerStore, org.mockito.Mockito.atLeast(3)).recordStage(
                eq(DOC_ID), eq(TOKEN), codes.capture(), labels.capture());
        java.util.List<String> landed = codes.getAllValues();
        assertTrue(landed.contains("start"), landed + " 缺提取启动行");
        assertTrue(landed.contains("bucket-1"), landed + " 缺归并结果行");
        assertTrue(landed.contains("reconcile-1"), landed + " 缺完整性复核行");
        assertTrue(labels.getAllValues().stream().anyMatch(l -> l.startsWith("归并结果 1/1")),
                "归并结果标签点名 j/m: " + labels.getAllValues());
        assertTrue(labels.getAllValues().stream().anyMatch(l -> l.startsWith("完整性复核 1/")),
                "完整性复核标签点名轮次与总数: " + labels.getAllValues());
    }

    @Test
    void fullyCoveredFirstRound_writesLedgerAndBurnsNoExtraRound() {
        stub(List.of(chunk(101L, 1, LOGIN_TEXT), chunk(102L, 2, EXPORT_TEXT)),
                "{\"requirements\":["
                        + "{\"title\":\"登录失败锁定\",\"evidence\":\"" + LOGIN_TEXT + "\"},"
                        + "{\"title\":\"报表按月归档\",\"evidence\":\"" + EXPORT_TEXT + "\"}]}");
        service.extract(message());
        verify(llmChain, times(1)).chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any());
        ArgumentCaptor<String> steps = ArgumentCaptor.forClass(String.class);
        verify(ledgerStore).upsertIfChanged(eq(DOC_ID), eq(TOKEN), steps.capture());
        assertTrue(steps.getValue().contains("\"state\":\"done\""), steps.getValue());
        assertFalse(steps.getValue().contains("\"state\":\"pending\""),
                "every chunk cited -> no pending step may remain: " + steps.getValue());
    }

    @Test
    void gapChunk_isReExtractedTargetedly_andMergedBeforeWriteBack() {
        stub(List.of(chunk(101L, 1, LOGIN_TEXT), chunk(102L, 2, EXPORT_TEXT)),
                RESP_LOGIN_ONLY, RESP_COVER_EXPORT);
        service.extract(message());
        verify(llmChain, times(2)).chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any());
        String secondPrompt = userPromptOfCall(2);
        assertTrue(secondPrompt.contains(EXPORT_TEXT), "gap round must show the gap chunk");
        assertFalse(secondPrompt.contains(LOGIN_TEXT),
                "gap round must NOT resend the already-covered chunk (no whole-package re-burn)");
        assertEquals(2, DocExtractTestSupport.capturedWrite(coreGateway, DOC_ID, TOKEN).size(), "gap-round item merges before write-back");
    }

    @Test
    void zeroGainRound_stopsEarly_neverLoopsToTheCap() {
        stub(List.of(chunk(101L, 1, LOGIN_TEXT), chunk(102L, 2, EXPORT_TEXT)),
                RESP_LOGIN_ONLY, RESP_UNCITED);
        service.extract(message());
        // default cap is 3 but the first reconcile round gained nothing -> stop at 2 calls
        verify(llmChain, times(2)).chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any());
        ArgumentCaptor<String> steps = ArgumentCaptor.forClass(String.class);
        verify(ledgerStore).upsertIfChanged(eq(DOC_ID), eq(TOKEN), steps.capture());
        assertTrue(steps.getValue().contains("pending"), "residual gap stays honest in the ledger");
        // recalibration: the uncited second row keeps a null anchor and the
        // factory-on landing gate holds it back — 1 anchored row lands (stop-early and the
        // pending-ledger assertions above are unchanged).
        assertEquals(1, DocExtractTestSupport.capturedWrite(coreGateway, DOC_ID, TOKEN).size());
    }

    /**
 * (live 8905 root cause): a dense block with several
 * enumerated entries was declared covered after a single citing item, so
 * the reconcile loop stopped after round 1 and 69 chapters' worth of
 * requirements were silently lost. Coverage now has item-count granularity
 * and the early stop compares the remaining shortfall, not the gap count —
 * a round that cites one more entry of a three-entry block is progress and
 * the loop must keep going until the block is satisfied or gains stop.
 */
    @Test
    void denseThreeEntryBlock_roundsContinueWhileShortfallShrinks_neverStopsAtOneItem() {
        // Proposals keep the count at or above the ledger shortfall so the
        // in-round dispute stays quiet here — this test guards the
        // cross-round progression face (the revision face is covered
        // by the two cases below).
        stub(List.of(chunk(101L, 1, DENSE_THREE)),
                RESP_LOGIN_ONLY,
                "{\"requirements\":["
                        + "{\"title\":\"水印不可关\",\"evidence\":\"水印不得关闭\"},"
                        + "{\"title\":\"无关条目\",\"evidence\":\"这段摘录不在任何分块里\"}]}",
                RESP_COVER_EXPORT);
        service.extract(message());
        // 1 first-pass call + reconcile round 1 (2/3 cited, still short) + round 2 (3/3)
        verify(llmChain, times(3)).chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any());
        ArgumentCaptor<String> steps = ArgumentCaptor.forClass(String.class);
        verify(ledgerStore).upsertIfChanged(eq(DOC_ID), eq(TOKEN), steps.capture());
        assertFalse(steps.getValue().contains("\"state\":\"pending\""),
                "all three entries cited -> ledger must show the block done, not pending: "
                        + steps.getValue());
        assertEquals(3, DocExtractTestSupport.capturedWrite(coreGateway, DOC_ID, TOKEN).size(), "every reconcile-round item lands before write-back");
    }

    /**
 * : the reconcile ring carries a second mechanical dispute beside
 * the JSON-validity check — the draft's item count against the coverage
 * ledger's shortfall for the round. The count source is 's
 * coverageDeficit, never re-counted here (R40), and the check itself burns
 * zero model calls. A short-count draft earns a targeted revision inside
 * the same round instead of silently converging and grinding the whole
 * round cap again from scratch.
 */
    @Test
    void shortCountGapProposal_getsTargetedCountRevisionWithinTheRound() {
        stub(List.of(chunk(101L, 1, DENSE_THREE)),
                RESP_LOGIN_ONLY,
                RESP_COVER_EXPORT, // one item where the books still owe two
                DRAFT_EXPORT_AND_WATERMARK); // the in-round revision pays it back
        service.extract(message());
        verify(llmChain, times(3)).chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any());
        String revisionPrompt = userPromptOfCall(3);
        assertTrue(revisionPrompt.contains("覆盖账本缺口 2 条"),
                "call 3 must be the count-dispute revision, got: " + revisionPrompt);
        boolean namedOnLog = logAppender.list.stream().anyMatch(e ->
                e.getFormattedMessage().contains("review count assertion"));
        assertTrue(namedOnLog,
                "the zero-model-call mechanical check must name itself on the log face");
        ArgumentCaptor<String> steps = ArgumentCaptor.forClass(String.class);
        verify(ledgerStore).upsertIfChanged(eq(DOC_ID), eq(TOKEN), steps.capture());
        assertFalse(steps.getValue().contains("\"state\":\"pending\""), steps.getValue());
        assertEquals(3, DocExtractTestSupport.capturedWrite(coreGateway, DOC_ID, TOKEN).size());
    }

    /**
 * (non-convergence face): a draft that stays short after the
 * kernel rounds surfaces the residual count dispute on the log/preview
 * ledger — never silent. What the round did attribute still lands and the
 * ledger keeps its honest pending shape (the pre-existing early-stop
 * posture, untouched).
 */
    @Test
    void persistentShortCount_surfacesResidualDispute_neverSilent() {
        stub(List.of(chunk(101L, 1, DENSE_THREE)),
                RESP_LOGIN_ONLY,
                RESP_UNCITED, // one item, unattributable, books owe two -> disputed
                RESP_UNCITED); // the revision stays short -> the ring ends unconverged
        service.extract(message());
        verify(llmChain, times(3)).chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any());
        boolean residualSurfaced = logAppender.list.stream().anyMatch(e ->
                e.getFormattedMessage().contains("supplement proposal not converged")
                        && e.getFormattedMessage().contains("覆盖账本缺口"));
        assertTrue(residualSurfaced,
                "the unconverged count dispute must ride the residual log face: "
                        + logAppender.list.stream()
                                .map(ILoggingEvent::getFormattedMessage)
                                .filter(m -> m.contains("补提")).toList());
        ArgumentCaptor<String> steps = ArgumentCaptor.forClass(String.class);
        verify(ledgerStore).upsertIfChanged(eq(DOC_ID), eq(TOKEN), steps.capture());
        assertTrue(steps.getValue().contains("pending"), steps.getValue());
        assertEquals(1, DocExtractTestSupport.capturedWrite(coreGateway, DOC_ID, TOKEN).size());
    }

    @Test
    void hotRoundsKey_capsTheLoop_defaultThreeRangeOneFive() {
        // restub: every round proposal now counts at or above the
        // ledger shortfall (a short draft would earn an in-round count
        // revision and skew the cap face this test measures). The round-1
        // draft pays two items, one unattributable, so chunk 103 stays the
        // honest pending gap under the cap.
        stub(List.of(chunk(101L, 1, LOGIN_TEXT),
                        chunk(102L, 2, "导出速度不超过五秒"),
                        chunk(103L, 3, EXPORT_TEXT)),
                RESP_LOGIN_ONLY,
                "{\"requirements\":["
                        + "{\"title\":\"导出速度\",\"evidence\":\"导出速度不超过五秒\"},"
                        + "{\"title\":\"无关条目\",\"evidence\":\"这段摘录不在任何分块里\"}]}");
        when(coreGateway.getSysConfig(KEY_ROUNDS)).thenReturn("1");
        service.extract(message());
        // cap 1 -> first round + exactly one reconcile round; chunk 103 remains gap
        verify(llmChain, times(2)).chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any());
        ArgumentCaptor<String> steps = ArgumentCaptor.forClass(String.class);
        verify(ledgerStore).upsertIfChanged(eq(DOC_ID), eq(TOKEN), steps.capture());
        assertTrue(steps.getValue().contains("块#103"), steps.getValue());
        assertTrue(steps.getValue().contains("pending"), steps.getValue());
    }

    @Test
    void garbageRoundsKey_fallsBackToDefault_three() {
        // restub: proposals meet each round's ledger count so the
        // cap face stays clean — one cited item per round plus filler that
        // keeps the draft count at the shortfall.
        stub(List.of(chunk(101L, 1, LOGIN_TEXT),
                        chunk(102L, 2, "导出速度不超过五秒"),
                        chunk(103L, 3, "水印不得关闭"),
                        chunk(104L, 4, EXPORT_TEXT)),
                RESP_LOGIN_ONLY,
                "{\"requirements\":["
                        + "{\"title\":\"导出速度\",\"evidence\":\"导出速度不超过五秒\"},"
                        + "{\"title\":\"甲\",\"evidence\":\"这段摘录不在任何分块里\"},"
                        + "{\"title\":\"乙\",\"evidence\":\"该条为演示外的占位文本\"}]}",
                "{\"requirements\":["
                        + "{\"title\":\"水印\",\"evidence\":\"水印不得关闭\"},"
                        + "{\"title\":\"丙\",\"evidence\":\"这段摘录同样不在分块里\"}]}",
                "{\"requirements\":[{\"title\":\"报表按月归档\",\"evidence\":\"" + EXPORT_TEXT + "\"}]}");
        when(coreGateway.getSysConfig(KEY_ROUNDS)).thenReturn("abc");
        service.extract(message());
        // default 3 reconcile rounds, each gaining coverage -> 1 + 3 calls
        verify(llmChain, times(4)).chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any());
    }

    @Test
    void ledgerDoorDown_neverFailsTheExtraction() {
        stub(List.of(chunk(101L, 1, LOGIN_TEXT)),
                "{\"requirements\":[{\"title\":\"登录失败锁定\",\"evidence\":\"" + LOGIN_TEXT + "\"}]}");
        doThrow(new RuntimeException("db down")).when(ledgerStore)
                .upsertIfChanged(eq(DOC_ID), eq(TOKEN), any());
        service.extract(message()); // must not throw
        assertEquals(1, DocExtractTestSupport.capturedWrite(coreGateway, DOC_ID, TOKEN).size());
    }

    /**
 * race guard: an E2E round deleted the document while the LLM rounds
 * were still burning. The coverage-ledger INSERT would then land on a
 * parent row that no longer exists (FK violation WARN storm per message
 * redelivery). The adjudicated posture is a presence door probed BEFORE
 * the ledger write: an explicit entity-gone rejection skips the write
 * with one honest line, and the downstream write-back failure path keeps
 * its own (already-demoted) semantics — the state machine is never welded
 * by the ledger face.
 */
    @Test
    void documentDeletedMidRun_skipsLedgerWrite_noForeignKeyNoise() {
        stub(List.of(chunk(101L, 1, LOGIN_TEXT)),
                "{\"requirements\":[{\"title\":\"登录失败锁定\",\"evidence\":\"" + LOGIN_TEXT + "\"}]}");
        when(coreGateway.getDocument(DOC_ID)).thenThrow(new ServiceException(
                "文档不存在（或已删除）", BizErrorCode.NOT_FOUND));
        service.extract(message());
        verify(ledgerStore, never()).upsertIfChanged(anyLong(), any(), any());
        boolean fkNoise = logAppender.list.stream().anyMatch(e ->
                e.getFormattedMessage().contains("coverage ledger persist failed"));
        assertFalse(fkNoise, "gone document must skip the write, not surface a door-failure WARN");
        boolean honestSkip = logAppender.list.stream().anyMatch(e ->
                e.getFormattedMessage().contains("coverage ledger skipped"));
        assertTrue(honestSkip, "the skip must be named on the log face (no silent drop)");
    }

    /**
     * Conservative face of the presence door: a core outage is NOT an
     * entity-gone signal. The document may well exist, so the ledger write
     * must still be attempted — the gate never quietly suppresses coverage
     * books because a door was briefly unreachable.
     */
    @Test
    void presenceDoorOutage_stillAttemptsLedgerWrite() {
        stub(List.of(chunk(101L, 1, LOGIN_TEXT)),
                "{\"requirements\":[{\"title\":\"登录失败锁定\",\"evidence\":\"" + LOGIN_TEXT + "\"}]}");
        when(coreGateway.getDocument(DOC_ID)).thenThrow(
                new ServiceException("core 服务不可达", BizErrorCode.AI_FORMAT_ERROR));
        service.extract(message());
        verify(ledgerStore).upsertIfChanged(eq(DOC_ID), eq(TOKEN), any());
    }

    @Test
    void storeAbsent_keepsLegacyZeroReconcileBehaviour() {
        DocExtractServiceImpl legacy = new DocExtractServiceImpl(
                coreGateway, llmChain, llmRouter, new ObjectMapper());
        stub(List.of(chunk(101L, 1, LOGIN_TEXT), chunk(102L, 2, EXPORT_TEXT)), RESP_LOGIN_ONLY);
        legacy.extract(message());
        // no ledger door wired = byte-identical old pipeline: one call, no reconcile
        verify(llmChain, times(1)).chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any());
        assertEquals(1, DocExtractTestSupport.capturedWrite(coreGateway, DOC_ID, TOKEN).size());
    }
}
