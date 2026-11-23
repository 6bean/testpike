package com.sixbean.testpike.aiservice.judge.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedger;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedgerStore;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenPreviewView;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.exception.ServiceException;
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
 * piece 2 (mirror of the spec-extract door): the judge QA-generation
 * door consumes the shared grouped-intake planner instead of rejecting
 * over-sized chunk sets outright. Gate {@code ai.judge.grouped-intake.enabled}
 * keeps the same tri-state semantics (missing row = on, only 0/false closes);
 * the idempotent merge key is the normalized question; each generation books
 * one fidelity row (task_type = qa-gen) through the shared ledger store.
 * The preview view's chunkCount face stays the source-chunk count — the LLM
 * call face is the group count, named here so the two can never drift.
 */
class EvalQuestionGenGroupedIntakeTest {

    private static final long DOC_ID = 92L;
    private static final long PROJECT_ID = 32L;
    private static final long OPERATOR_ID = 16L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final ExtractFidelityLedgerStore fidelityStore =
            mock(ExtractFidelityLedgerStore.class);
    private final EvalQuestionGenServiceImpl service = new EvalQuestionGenServiceImpl(
            coreGateway, llmChain, llmRouter, new ObjectMapper(), fidelityStore, null);

    private static final String VALID_LLM_JSON = """
            {"items":[{"question":"订单创建接口用什么方法？","answer":"POST /api/v1/orders",
            "sourceAnchor":"创建订单接口 POST /api/v1/orders"}]}""";

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG);
    }

    private static QaGenRequest request() {
        return new QaGenRequest(PROJECT_ID, DOC_ID, null, OPERATOR_ID);
    }

    private static Map<String, Object> chunk(int seq) {
        return Map.of("seq", seq, "chapter", "验收标准",
                "content", "创建订单接口 POST /api/v1/orders 事实片段" + seq);
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
        // Deterministic seal face for the fixture (the door's own
        // judge.qa-gen.group-overlap-ratio=0 removes the carried tail; the
        // overlap window itself stays pinned by the shared planner's tests).
        when(coreGateway.getSysConfig("judge.qa-gen.group-overlap-ratio"))
                .thenReturn("0");
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenReturn(new LlmChatResult(VALID_LLM_JSON, "openai", "routed-model",
                        100, 50, 20L));
    }

    /** Gate on: 25 chunks over the legacy cost gate (default 20) are NOT
     *  rejected — they seal into one token-bounded group (factory chunk
     *  ceiling 45), the duplicate question merges to one item, and the
     *  qa-gen fidelity row lands with the group count as its node face. */
    @Test
    void groupedIntakeOn_overCapChunks_singleGroupMergeAndBooksFidelity() {
        stubRun(chunks(25));

        QaGenPreviewView view = service.generate(request());

        verify(llmChain, times(1)).chat(any(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean());
        assertEquals(1, view.items().size(),
                "same normalized question across groups must merge idempotently");
        assertEquals(25, view.chunkCount(), "preview count face = source chunks");
        assertEquals(100, view.promptTokens());

        ArgumentCaptor<ExtractFidelityLedger.Entry> row =
                ArgumentCaptor.forClass(ExtractFidelityLedger.Entry.class);
        verify(fidelityStore).upsertIfChanged(row.capture());
        ExtractFidelityLedger.Entry entry = row.getValue();
        assertEquals(ExtractFidelityLedger.TASK_QA_GEN, entry.taskType());
        assertEquals(DOC_ID, entry.refId());
        assertEquals(25, entry.chunkCount());
        assertEquals(1, entry.nodeCount());
        assertEquals(100, entry.injectedTokens());
        assertEquals(1, entry.itemCount());
        assertEquals(1, entry.anchoredItemCount());
    }

    /** Gate explicitly false: legacy face byte-identical — over-cap chunks
     *  get the same human-readable rejection with zero LLM burn. */
    @Test
    void groupedIntakeOff_keepsLegacyCostGateRejectionVerbatim() {
        when(coreGateway.getSysConfig(EvalQuestionGenServiceImpl.KEY_GROUPED_INTAKE_ENABLED))
                .thenReturn("false");
        stubRun(chunks(25));

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.generate(request()));
        assertTrue(e.getMessage().contains("超过单次造题上限"),
                "closed door must answer the legacy rejection sentence");
        verify(llmChain, never()).chat(any(), anyLong(), any(), anyBoolean());
        verify(fidelityStore, never()).upsertIfChanged(any());
    }

    /** A3 single-source rule: caps read this door's own namespace
     *  (judge.qa-gen.*). A stored chunk ceiling of 5 seals 25/5 = 5 groups. */
    @Test
    void domainGroupMaxChunksKey_consumedBySharedPlanner() {
        when(coreGateway.getSysConfig("judge.qa-gen.group-max-chunks")).thenReturn("5");
        stubRun(chunks(25));

        service.generate(request());

        verify(llmChain, times(5)).chat(any(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean());
    }
}
