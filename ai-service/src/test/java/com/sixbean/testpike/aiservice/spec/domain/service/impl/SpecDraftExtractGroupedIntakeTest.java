package com.sixbean.testpike.aiservice.spec.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedger;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedgerStore;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiSpecExtractMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * piece 2: the spec-extract door consumes the shared grouped-intake
 * planner (single-sourced in {@code ContextBudgetPlanner.intakePlanner})
 * instead of rejecting over-sized chunk sets outright. Tri-state gate
 * {@code ai.spec.grouped-intake.enabled}: a missing row keeps the door on,
 * only an explicit 0/false closes it back to the legacy per-chunk face with
 * the cost-gate rejection verbatim. Idempotent merge across groups is keyed
 * by method+path; every generation books one fidelity row
 * (task_type = spec-extract) through the shared ledger store.
 * <p>L2 scope: infrastructure mocks only (gateway/chain/router) — the real
 * extraction quality stays on the L3/L4 planes, same face as the legacy
 * {@link SpecDraftExtractServiceImplTest}.
 */
class SpecDraftExtractGroupedIntakeTest {

    private static final long DOC_ID = 91L;
    private static final long PROJECT_ID = 31L;
    private static final long OPERATOR_ID = 15L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final ExtractFidelityLedgerStore fidelityStore =
            mock(ExtractFidelityLedgerStore.class);
    private final SpecDraftExtractServiceImpl service = new SpecDraftExtractServiceImpl(
            coreGateway, llmChain, llmRouter, new ObjectMapper(), fidelityStore);

    private static final String VALID_LLM_JSON = """
            {"items":[{"method":"POST","path":"/api/v1/orders","title":"创建订单",
            "sourceAnchor":"创建订单接口 POST /api/v1/orders"}]}""";

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG);
    }

    private static AiSpecExtractMessage message() {
        return new AiSpecExtractMessage(DOC_ID, PROJECT_ID, OPERATOR_ID, null, "pub-token-1");
    }

    private static Map<String, Object> chunk(int seq) {
        return Map.of("seq", seq, "chapter", "接口清单",
                "content", "创建订单接口 POST /api/v1/orders 片段" + seq);
    }

    private static List<Map<String, Object>> chunks(int n) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(chunk(i));
        }
        return out;
    }

    private void stubRun(List<Map<String, Object>> chunks) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(chunks);
        // Deterministic seal face for the fixture: the door's own overlap
        // key (apitest.spec-extract.group-overlap-ratio=0) removes the
        // carried tail so group counts equal the pure chunk-cap ceil. The
        // overlap window itself is pinned by the shared planner's own tests.
        when(coreGateway.getSysConfig("apitest.spec-extract.group-overlap-ratio"))
                .thenReturn("0");
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenReturn(new LlmChatResult(VALID_LLM_JSON, "openai", "routed-model",
                        100, 50, 20L));
    }

    /** Gate on (missing row = factory on): 61 chunks over the legacy 50 cap
     *  are NOT rejected — the shared chunk ceiling (factory 45) seals exactly
     *  two groups, cross-group duplicates merge by method+path, and the
     *  fidelity row lands with the spec-extract plane tag. */
    @Test
    void groupedIntakeOn_overCapChunks_groupedExtractionIdempotentMergeAndBooksFidelity() {
        stubRun(chunks(61));

        service.extract(message());

        verify(llmChain, times(2)).chat(any(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> written =
                ArgumentCaptor.forClass(List.class);
        verify(coreGateway).writeSpecDrafts(eq(DOC_ID), eq(OPERATOR_ID), written.capture());
        assertEquals(1, written.getValue().size(),
                "same method+path across groups must merge idempotently (first wins)");
        assertEquals("B", written.getValue().get(0).get("channel"));

        ArgumentCaptor<ExtractFidelityLedger.Entry> row =
                ArgumentCaptor.forClass(ExtractFidelityLedger.Entry.class);
        verify(fidelityStore).upsertIfChanged(row.capture());
        ExtractFidelityLedger.Entry entry = row.getValue();
        assertEquals(ExtractFidelityLedger.TASK_SPEC_EXTRACT, entry.taskType());
        assertEquals(DOC_ID, entry.refId());
        assertEquals(61, entry.chunkCount());
        assertEquals(2, entry.nodeCount(), "the accounted node face is the group count");
        assertEquals(200, entry.injectedTokens(), "two booked calls x 100 prompt tokens");
        assertTrue(entry.sourceTokens() > 0);
        assertEquals(1, entry.itemCount());
        assertEquals(1, entry.anchoredItemCount());
    }

    /** Gate explicitly false: the legacy face stays byte-identical — the cost
     *  gate rejects over-cap chunks with the same human-readable sentence and
     *  zero LLM calls are burned. */
    @Test
    void groupedIntakeOff_keepsLegacyCostGateRejectionVerbatim() {
        when(coreGateway.getSysConfig(SpecDraftExtractServiceImpl.KEY_GROUPED_INTAKE_ENABLED))
                .thenReturn("false");
        stubRun(chunks(61));

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.extract(message()));
        assertTrue(e.getMessage().contains("超过单次抽取上限"),
                "closed door must answer the legacy rejection sentence");
        verify(llmChain, never()).chat(any(), anyLong(), any(), anyBoolean());
        verify(coreGateway, never()).writeSpecDrafts(anyLong(), anyLong(), any());
        verify(fidelityStore, never()).upsertIfChanged(any());
    }

    /** Tri-state semantics of the directive family: unparsable gate values
     *  keep the door on (only an explicit 0/false closes), so a mistyped
     *  switch can never silently resurrect the outright rejection. */
    @Test
    void groupedIntakeGateGarbageValue_fallsBackToOn() {
        when(coreGateway.getSysConfig(SpecDraftExtractServiceImpl.KEY_GROUPED_INTAKE_ENABLED))
                .thenReturn("abc");
        stubRun(chunks(61));

        service.extract(message());

        verify(llmChain, times(2)).chat(any(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean());
    }

    /** A3 single-source rule: the grouping caps read THIS door's own
     *  namespace (apitest.spec-extract.*), never the ai.doc.* rows. A stored
     *  chunk ceiling of 10 must therefore seal ceil(61/10)=7 groups. */
    @Test
    void domainGroupMaxChunksKey_consumedBySharedPlanner() {
        when(coreGateway.getSysConfig("apitest.spec-extract.group-max-chunks"))
                .thenReturn("10");
        stubRun(chunks(61));

        service.extract(message());

        verify(llmChain, times(7)).chat(any(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean());
    }
}
