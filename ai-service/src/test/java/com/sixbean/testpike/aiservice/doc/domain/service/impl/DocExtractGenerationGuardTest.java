package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.endpoint;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.chunk;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.VALID_JSON;

/**
 * segment B — extraction generation self-check guard (17 TC-XQ-05,
 * 18 IT-XQ-01). A superseded run (a preempt/newer submit landed while this
 * consumer was mid-pipeline) must notice at the agreed seams and abort
 * itself: NO further LLM calls, NO result write-back, NO failure write-back
 * (failExtraction would roll back the newer generation's status), and the
 * method must return quietly so the MQ consumer acks instead of retrying.
 * <p>An unreadable read door (null) means "keep running the same
 * generation" — a core hiccup must never kill a valid run.
 * <p>L2 isolation on infrastructure seams (CoreGateway door, LlmFallbackChain
 * provider call, ProjectLlmRouter) — the guard logic itself runs for real.
 */
class DocExtractGenerationGuardTest {

    private static final long DOC_ID = 93L;
    private static final long PROJECT_ID = 33L;
    private static final String TOKEN = "gen-token-guard-001";
    private static final String FOREIGN = "gen-token-guard-newer-gen";
    private final DocExtractTestFixtures fixtures = new DocExtractTestFixtures(DOC_ID, PROJECT_ID, TOKEN);
    private final CoreGateway coreGateway = fixtures.coreGateway();
    private final LlmFallbackChain llmChain = fixtures.llmChain();
    private final ProjectLlmRouter llmRouter = fixtures.llmRouter();
    private final DocExtractServiceImpl service = fixtures.pipeline();

    /** One small in-budget chunk: straight to the regeneration loop. */
    private void stubSmallChunks() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID))
                .thenReturn(List.of(chunk(1, "登录 > 异常", "连续五次输入错误密码，账号锁定十五分钟。")));
    }

    /** Over-budget single chunk: forces the F5 Map-Reduce compression plane. */
    private void stubOverBudgetChunk() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID))
                .thenReturn(List.of(chunk(1, "超长章节", "字".repeat(200_000))));
    }

    /** A foreign live token at the loop-entry seam = abort before any LLM call. */
    @Test
    void 他轮次在场_进提取循环前自检中止_零LLM零回写且不抛出() {
        stubSmallChunks();
        when(coreGateway.fetchExtractToken(DOC_ID)).thenReturn(FOREIGN);

        assertDoesNotThrow(() -> service.extract(fixtures.message()));

        verify(llmChain, never()).chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean(), any());
        verify(coreGateway, never()).writeExtractionResult(eq(DOC_ID), any(), any());
        verify(coreGateway, never()).markExtractionFailed(eq(DOC_ID), any(), any());
    }

    /** Same live token = the guard lets the normal run through untouched. */
    @Test
    void 同轮次自检放行_照常一次调用并回写() {
        stubSmallChunks();
        when(coreGateway.fetchExtractToken(DOC_ID)).thenReturn(TOKEN);
        fixtures.stubLlmResponses(VALID_JSON);

        service.extract(fixtures.message());

        verify(llmChain, times(1)).chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean(), any());
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), any(), eq(TOKEN));
        verify(coreGateway, never()).markExtractionFailed(eq(DOC_ID), any(), any());
    }

    /** Unreadable door (null) keeps the same generation running — never abort. */
    @Test
    void 读门不可达_按同代续跑_自检确实发生() {
        stubSmallChunks();
        fixtures.stubLlmResponses(VALID_JSON);

        service.extract(fixtures.message());

        // The door is probed at both agreed seams before the first provider
        // call (loop entry + attempt body head) — a null answer keeps both
        // reads harmless and the generation running.
        verify(coreGateway, times(2)).fetchExtractToken(DOC_ID);
        verify(llmChain, times(1)).chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean(), any());
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), any(), eq(TOKEN));
        verify(coreGateway, never()).markExtractionFailed(eq(DOC_ID), any(), any());
    }

    /** Preemption lands between rounds: the attempt-body seam stops round 2. */
    @Test
    void 首轮烧完才出现新轮次_次轮体首自检中止_结果永不回写() {
        stubSmallChunks();
        when(coreGateway.fetchExtractToken(DOC_ID)).thenReturn(TOKEN, TOKEN, FOREIGN);
        fixtures.stubLlmResponses("输出不是 JSON 的散文", VALID_JSON);

        assertDoesNotThrow(() -> service.extract(fixtures.message()));

        verify(llmChain, times(1)).chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean(), any());
        verify(coreGateway, never()).writeExtractionResult(eq(DOC_ID), any(), any());
        verify(coreGateway, never()).markExtractionFailed(eq(DOC_ID), any(), any());
    }

    /** F5 compression plane is guarded too: a foreign token stops Map before
     *  the first summarisation call (the 300s-style stall burns nothing). */
    @Test
    void 超预算分组摘要同样过自检_他轮次时摘要零调用() {
        stubOverBudgetChunk();
        when(coreGateway.fetchExtractToken(DOC_ID)).thenReturn(FOREIGN);

        assertDoesNotThrow(() -> service.extract(fixtures.message()));

        verify(coreGateway, times(1)).fetchExtractToken(DOC_ID);
        verify(llmChain, never()).chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean(), any());
        verify(coreGateway, never()).writeExtractionResult(eq(DOC_ID), any(), any());
        verify(coreGateway, never()).markExtractionFailed(eq(DOC_ID), any(), any());
    }
}
