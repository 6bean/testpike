package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.DocGroupSummaryCache;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
 * segment B — per-group summary resume on the F5 Map plane. A cached
 * group summary (loadGroups hit for the first group) must skip THAT group's
 * model call and persist only the groups it computed (saveGroup per computed
 * group) — the mid-round failure re-burn pattern (document 8491 attempt 2
 * re-summarising the whole 94k Map round) is what this granularity forbids.
 * <p>Zero cache = every group summarised once, each group persisted once, the
 * whole-round row persisted once at the end. Switch off = the per-group seams
 * are never consulted while the whole-round face runs normally (the cache
 * object self-gates internally).
 * <p>L2 isolation on infrastructure seams (CoreGateway door, LlmFallbackChain
 * provider call, ProjectLlmRouter, cache); tokenCounter/skillBridge/mcpBridge
 * null = the character-heuristic planner face, exactly as the sibling
 * generation-guard fixture. Two 100k-char chunks group into 2 groups at the
 * default budget and MultiLevelReducePlanner.plan(2) yields levels=1 (zero
 * merge rounds), so the summary-call count is asserted precisely.
 */
class DocExtractGroupSummaryResumeTest {

    private static final long DOC_ID = 95L;
    private static final long PROJECT_ID = 35L;
    private static final String TOKEN = "gen-token-resume-001";
    private final DocExtractTestFixtures fixtures = new DocExtractTestFixtures(DOC_ID, PROJECT_ID, TOKEN);
    private final CoreGateway coreGateway = fixtures.coreGateway();
    private final LlmFallbackChain llmChain = fixtures.llmChain();
    private final ProjectLlmRouter llmRouter = fixtures.llmRouter();
    private final DocGroupSummaryCache cache = mock(DocGroupSummaryCache.class);
    private final DocExtractServiceImpl service = fixtures.pipeline(cache);

    /** Two over-budget chunks: greedy grouping yields exactly 2 groups. The
 * whole-round seam is pinned to a genuine miss (null) — an unstubbed mock
 * would hand back an EMPTY list, which the real cache object can never
 * return for a miss, and the whole Map round would be skipped as a bogus
 */
    private void stubTwoOversizedChunks() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                chunk(1, "第一章", "字".repeat(100_000)),
                chunk(2, "第二章", "字".repeat(100_000))));
        when(cache.load(DOC_ID, cacheTok)).thenReturn(null);
    }

    /** Counts only the summary-plane calls (system message carries the F5
     *  marker). totalProviderCalls = every chat on this signature for the round:
     *  a cached group shortens the round, so the arithmetic is per-test, never
     *  a single hard-coded face. */
    private long summaryCalls(int totalProviderCalls) {
        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, times(totalProviderCalls)).chat(captor.capture(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean(), any());
        return captor.getAllValues().stream()
                .filter(r -> r.messages().get(0).content().contains(DocExtractServiceImpl.SUMMARY_MARKER))
                .count();
    }

    /** Cached first group = one skipped summary burn; only group 2 gets persisted. */
    /** Prompt-generation suffixed token, probed once at construction (outside Mockito matcher evaluation). */
    private final String cacheTok = service.cacheToken(TOKEN);

    @Test
    void 缓存含首组_摘要外呼仅残缺组_首组禁重烧() {
        stubTwoOversizedChunks();
        when(cache.enabled()).thenReturn(true);
        when(cache.loadGroups(DOC_ID, cacheTok)).thenReturn(Map.of(
                1, Map.of("seq", 1, "chapter", "第一章", "content", "cached-summary-one")));
        fixtures.stubLlmResponses("第二章摘要文本", VALID_JSON);

        service.extract(fixtures.message());

        // arithmetic: 1 overview burn + 2 grouped raw
        // requirement calls (the fold keeps the written face correct downstream).
        assertEquals(1, summaryCalls(4), "cached group must not re-burn a summary call");
        verify(cache, times(1)).saveGroup(eq(DOC_ID), eq(cacheTok), eq(2), any());
        verify(cache, never()).saveGroup(eq(DOC_ID), eq(cacheTok), eq(1), any());
        verify(cache, times(1)).save(eq(DOC_ID), eq(cacheTok), any());
    }

    /** Zero cache = both groups summarised, each persisted, whole round saved once. */
    @Test
    void 零缓存_逐组外呼逐组落盘且整轮收尾落一次() {
        stubTwoOversizedChunks();
        when(cache.enabled()).thenReturn(true);
        when(cache.loadGroups(DOC_ID, cacheTok)).thenReturn(Map.of());
        fixtures.stubLlmResponses("第一章摘要文本", "第二章摘要文本", VALID_JSON);

        service.extract(fixtures.message());

        // +3 arithmetic: 2 overview burns + 2 grouped raw calls
        // + 1 settled merge-ring proposal on the shared colliding title.
        assertEquals(2, summaryCalls(5));
        verify(cache, times(1)).saveGroup(eq(DOC_ID), eq(cacheTok), eq(1), any());
        verify(cache, times(1)).saveGroup(eq(DOC_ID), eq(cacheTok), eq(2), any());
        verify(cache, times(1)).save(eq(DOC_ID), eq(cacheTok), any());
    }

    /** Switch off = per-group seams never consulted; whole-round face untouched. */
    @Test
    void 开关关闭态逐组缝零咨询整轮面照常() {
        stubTwoOversizedChunks();
        when(cache.enabled()).thenReturn(false);
        fixtures.stubLlmResponses("第一章摘要文本", "第二章摘要文本", VALID_JSON);

        service.extract(fixtures.message());

        // +3 arithmetic: 2 overview burns + 2 grouped raw calls
        // + 1 settled merge-ring proposal on the shared colliding title.
        assertEquals(2, summaryCalls(5));
        verify(cache, never()).loadGroups(anyLong(), any());
        verify(cache, never()).saveGroup(anyLong(), any(), anyInt(), any());
        verify(cache, times(1)).load(eq(DOC_ID), eq(cacheTok));
        verify(cache, times(1)).save(eq(DOC_ID), eq(cacheTok), any());
    }
}
