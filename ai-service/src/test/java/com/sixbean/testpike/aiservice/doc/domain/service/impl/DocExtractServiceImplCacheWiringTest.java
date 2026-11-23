package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.util.LlmExtractMarkers;
import com.sixbean.testpike.aiservice.doc.domain.component.ModelAdaptiveBudget;
import com.sixbean.testpike.aiservice.doc.domain.component.DocGroupSummaryCache;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AP-1 (3) cache wiring on the extraction orchestration seam .
 * CoreGateway/LlmFallbackChain/DocGroupSummaryCache are infrastructure
 * seams (HTTP / provider / DB gateways) — the compression assembly under test
 * runs real. Over-budget fixture mirrors DocExtractServiceImplE8Test: token
 * budget 60 forces the Map-Reduce branch with two-token chunk groups.
 */
class DocExtractServiceImplCacheWiringTest {

    private static final long DOC_ID = 12L;
    private static final long PROJECT_ID = 3L;
    private static final String TOKEN = "cache-wiring-token";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final DocGroupSummaryCache cache = mock(DocGroupSummaryCache.class);

    private DocExtractServiceImpl serviceWithCache() {
        return new DocExtractServiceImpl(coreGateway, llmChain, llmRouter, new ObjectMapper(),
                new com.sixbean.testpike.aiservice.llm.domain.component.TokenCounter(),
                null, null, cache);
    }

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_PROJECT, 2000);
    }

    /**
 * the over-budget branch is now driven by the adaptive
 * window (catalog limit 2000 x safety factor 0.1 = 200 tokens). 2000 is
 * deliberately wide: this fixture's ~1105-token summary prompt stays below
 * the prompt guard so only the budget (never the guard) trips the Map round.
 */
    private void stubRouteAndChunks(List<Map<String, Object>> chunks) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(chunks);
        when(coreGateway.getSysConfig(ModelAdaptiveBudget.KEY_SAFETY_FACTOR)).thenReturn("0.1");
    }

    /** 3 chunks over the 105-token adaptive budget → Map-Reduce branch with summary calls. */
    private static List<Map<String, Object>> overBudgetChunks() {
        String filler = "需求说明段落，".repeat(120); // ~ 2.4k chars, safely above the tiny budget
        return List.of(
                Map.of("seq", 1, "chapter", "第一章", "content", filler),
                Map.of("seq", 2, "chapter", "第二章", "content", filler),
                Map.of("seq", 3, "chapter", "第三章", "content", filler));
    }

    /** Summary/system marker routed answers: DOC_EXTRACT_SUMMARY → plain text, else JSON. */
    private void stubLlm() {
        when(llmChain.chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any())).thenAnswer(inv -> {
            ChatCompletionRequest req = inv.getArgument(0);
            String system = req.messages().get(0).content();
            if (system.contains(LlmExtractMarkers.DOC_EXTRACT_SUMMARY)) {
                return new LlmChatResult("摘要正文", "openai", "routed-model", 10, 5, 1L);
            }
            return new LlmChatResult("{\"requirements\":[{\"title\":\"需求0\","
                    + "\"acceptanceCriteria\":\"标准0\",\"chapter\":\"章节\",\"confidence\":0.8}]}",
                    "openai", "routed-model", 10, 5, 1L);
        });
    }

    private static AiDocExtractMessage message() {
        return new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN);
    }

    /** Prompt-generation suffixed token, probed once at construction (outside Mockito matcher evaluation). */
    private final String cacheTok = serviceWithCache().cacheToken(TOKEN);

    @Test
    void 缓存命中时零摘要调用且提取照常完成() {
        stubRouteAndChunks(overBudgetChunks());
        stubLlm();
        when(cache.load(DOC_ID, cacheTok)).thenReturn(List.of(
                Map.of("seq", 1, "chapter", "第一章（合并 3 个分块）", "content", "cached-summary"),
                Map.of("seq", 2, "chapter", "第二章", "content", "cached-summary-2")));

        serviceWithCache().extract(message());

        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, org.mockito.Mockito.atLeastOnce()).chat(captor.capture(), eq(PROJECT_ID),
                any(), anyBoolean(), any());
        long summaryCalls = captor.getAllValues().stream()
                .filter(r -> r.messages().get(0).content().contains(LlmExtractMarkers.DOC_EXTRACT_SUMMARY))
                .count();
        assertEquals(0, summaryCalls, "cache hit must skip the whole Map/Reduce round");
        // a hit is not re-persisted (row already present)
        verify(cache, never()).save(anyLong(), any(), any());
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), org.mockito.ArgumentMatchers.any(), eq(TOKEN));
    }

    @Test
    void 缓存未命中走正常Map轮并落盘结果() {
        stubRouteAndChunks(overBudgetChunks());
        stubLlm();
        when(cache.load(DOC_ID, cacheTok)).thenReturn(null);

        serviceWithCache().extract(message());

        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, org.mockito.Mockito.atLeastOnce()).chat(captor.capture(), eq(PROJECT_ID),
                any(), anyBoolean(), any());
        long summaryCalls = captor.getAllValues().stream()
                .filter(r -> r.messages().get(0).content().contains(LlmExtractMarkers.DOC_EXTRACT_SUMMARY))
                .count();
        assertTrue(summaryCalls >= 1, "miss must run the Map round, saw " + summaryCalls);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> saved = ArgumentCaptor.forClass(List.class);
        verify(cache).save(eq(DOC_ID), eq(cacheTok), saved.capture());
        assertFalse(saved.getValue().isEmpty());
        assertEquals("摘要正文", saved.getValue().get(0).get("content"));
    }

    @Test
    void 无缓存件旧装配零漂移() {
        // 7-arg constructor (cache absent) must keep the pre-slice behaviour: Map round runs,
        // no cache interaction is even possible.
        stubRouteAndChunks(overBudgetChunks());
        stubLlm();
        DocExtractServiceImpl legacy = new DocExtractServiceImpl(coreGateway, llmChain, llmRouter,
                new ObjectMapper(),
                new com.sixbean.testpike.aiservice.llm.domain.component.TokenCounter());
        legacy.extract(message());
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), org.mockito.ArgumentMatchers.any(), eq(TOKEN));
    }
}
