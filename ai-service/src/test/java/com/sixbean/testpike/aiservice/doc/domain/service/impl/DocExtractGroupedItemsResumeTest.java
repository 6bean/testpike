package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.sixbean.testpike.aiservice.doc.domain.component.DocGroupSummaryCache;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
 * remediation ② — per-group items resume on the grouped raw plane
 * . A cached group's requirement items must skip THAT
 * group's final-bucket plane entirely (zero model burn) and only the missing
 * group gets computed — and every computed group is persisted to disk right
 * where it finished, so a mid-run failure resumes instead of re-burning the
 * already extracted groups of the same generation (the 17-group document
 * pattern the summary plane already fixed for ).
 * <p>Summary side of the round is pinned fully cached here so the provider
 * arithmetic isolates the raw plane: cached summaries = zero overview burns.
 * The two colliding same-title rows (cached group 1 + fresh group 2) still
 * settle through the merge ring — one proposal call, unchanged semantics.
 * <p>L2 isolation on the same infrastructure seams as the sibling summary
 * resume test (CoreGateway door, LlmFallbackChain provider call,
 * ProjectLlmRouter, cache); two 100k-char chunks group into exactly 2 groups.
 */
class DocExtractGroupedItemsResumeTest {

    private static final long DOC_ID = 97L;
    private static final long PROJECT_ID = 37L;
    private static final String TOKEN = "gen-token-items-resume-001";
    private final DocExtractTestFixtures fixtures = new DocExtractTestFixtures(DOC_ID, PROJECT_ID, TOKEN);
    private final CoreGateway coreGateway = fixtures.coreGateway();
    private final LlmFallbackChain llmChain = fixtures.llmChain();
    private final ProjectLlmRouter llmRouter = fixtures.llmRouter();
    private final DocGroupSummaryCache cache = mock(DocGroupSummaryCache.class);
    private final DocExtractServiceImpl service = fixtures.pipeline(cache);

    /** One extracted requirement row, same title as the fixture VALID_JSON so
     *  the cached and freshly computed groups collide into one merge cluster.
     *  Mutable collections mirror what the real cache hands back (Jackson). */
    private static List<Map<String, Object>> cachedGroupItems() {
        Map<String, Object> row = new HashMap<>();
        row.put("title", "锁定异常登录");
        row.put("acceptanceCriteria", "连续五次错误锁定");
        row.put("chapter", "登录 > 异常");
        row.put("confidence", 0.8);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(row);
        return items;
    }

    /** Two over-budget chunks with BOTH group summaries already cached:
     *  the Map round burns zero overview calls, so every provider call in
     *  these tests belongs to the grouped raw plane or the merge ring. */
    private void stubChunksWithCachedSummaries() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                chunk(1, "第一章", "字".repeat(100_000)),
                chunk(2, "第二章", "字".repeat(100_000))));
        when(cache.load(DOC_ID, cacheTok)).thenReturn(null);
        when(cache.loadGroups(DOC_ID, cacheTok)).thenReturn(Map.of(
                1, Map.of("seq", 1, "chapter", "第一章", "content", "cached-summary-one"),
                2, Map.of("seq", 2, "chapter", "第二章", "content", "cached-summary-two")));
    }

    /** Counts only the summary-plane calls (system message carries the F5
     *  marker) out of exactly {@code total} provider chats on this signature. */
    private long summaryCalls(int total) {
        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, times(total)).chat(captor.capture(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean(), any());
        return captor.getAllValues().stream()
                .filter(r -> r.messages().get(0).content().contains(DocExtractServiceImpl.SUMMARY_MARKER))
                .count();
    }

    /** Cached first group = zero raw burn for it; only group 2 lands on disk. */
    /** Prompt-generation suffixed token, probed once at construction (outside Mockito matcher evaluation). */
    private final String cacheTok = service.cacheToken(TOKEN);

    @Test
    void 条目缓存含首组_该组零直提外呼且仅残缺组落盘() {
        stubChunksWithCachedSummaries();
        when(cache.enabled()).thenReturn(true);
        when(cache.loadItemGroups(DOC_ID, cacheTok))
                .thenReturn(Map.of(1, cachedGroupItems()));
        fixtures.stubLlmResponses(VALID_JSON);

        service.extract(fixtures.message());

        // 1 raw call (group 2 only) + 1 settled merge-ring proposal; zero summary burns.
        assertEquals(0, summaryCalls(2), "cached items group must not re-burn a raw call");
        verify(cache, times(1)).saveItemGroup(eq(DOC_ID), eq(cacheTok), eq(2), any());
        verify(cache, never()).saveItemGroup(eq(DOC_ID), eq(cacheTok), eq(1), any());
    }

    /** Zero items cache = both groups burned once, each persisted on the spot. */
    @Test
    void 零条目缓存_逐组外呼逐组落盘() {
        stubChunksWithCachedSummaries();
        when(cache.enabled()).thenReturn(true);
        when(cache.loadItemGroups(DOC_ID, cacheTok)).thenReturn(Map.of());
        fixtures.stubLlmResponses(VALID_JSON);

        service.extract(fixtures.message());

        // 2 raw calls + 1 settled merge-ring proposal; zero summary burns.
        assertEquals(0, summaryCalls(3));
        verify(cache, times(1)).saveItemGroup(eq(DOC_ID), eq(cacheTok), eq(1), any());
        verify(cache, times(1)).saveItemGroup(eq(DOC_ID), eq(cacheTok), eq(2), any());
    }
}
