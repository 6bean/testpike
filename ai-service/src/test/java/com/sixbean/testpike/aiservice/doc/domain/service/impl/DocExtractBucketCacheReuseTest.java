package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.sixbean.testpike.aiservice.doc.domain.component.DocBucketResultCache;
import com.sixbean.testpike.aiservice.doc.domain.component.DocGroupSummaryCache;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.chunkWithId;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.endpoint;

/**
 * wiring — the final-extract bucket plane reads the bucket-result
 * cache BEFORE burning a provider call and stores every freshly computed
 * bucket. The provider seam is the mocked LLM transport (L2 infrastructure
 * isolation, same harness as the group-plane fan-out test); the reuse bookface
 * is a plain map behind the mocked cache so the assertions measure exactly
 * one thing: WHICH buckets still reach the model.
 * <p>Spectrum:
 * <ul>
 * <li>run 1: two buckets, two provider calls;</li>
 * <li>run 2 same content: ZERO provider calls, landed items byte-identical
 * (the unchanged-bucket-zero-calls contract);</li>
 * <li>run 3 with ONE changed chapter: exactly the changed bucket recomputes;</li>
 * <li>cache OFF: every run burns the full face again (byte-identical legacy).</li>
 * </ul>
 */
class DocExtractBucketCacheReuseTest {

    private static final long DOC_ID = 99L;
    private static final long PROJECT_ID = 39L;

    private static final String JSON_GROUP_ONE = """
            {"requirements":[{"title":"令牌过期策略","acceptanceCriteria":"令牌五分钟后强制重新认证",\
             "chapter":"第一章","confidence":0.9,"evidence":"会话令牌五分钟过期"}]}""";
    private static final String JSON_GROUP_TWO = """
            {"requirements":[{"title":"订单退款时效","acceptanceCriteria":"退款七个工作日内原路退回",\
             "chapter":"第二章","confidence":0.9,"evidence":"订单退款七个工作日内原路退回"}]}""";

    private final List<ChatCompletionRequest> providerCalls = new CopyOnWriteArrayList<>();
    private final Map<String, List<Map<String, Object>>> bucketStore = new ConcurrentHashMap<>();
    private final AtomicInteger loads = new AtomicInteger();

    private LlmChatResult reply(ChatCompletionRequest request) {
        providerCalls.add(request);
        String system = request.messages().get(0).content();
        if (system.contains(DocExtractServiceImpl.SUMMARY_MARKER)) {
            return new LlmChatResult("转述概览文本", "openai", "routed-model", 10, 5, 1L);
        }
        boolean firstGroup = request.messages().stream()
                .anyMatch(m -> m.content() != null && m.content().contains("会话令牌"));
        return new LlmChatResult(firstGroup ? JSON_GROUP_ONE : JSON_GROUP_TWO,
                "openai", "routed-model", 100, 50, 20L);
    }

    /** Cache seam with a deterministic test key: the FIRST block's content
     *  plus the model — enough to observe "which bucket changed" while the
     *  real key semantics are pinned by the component test on the DB face. */
    @SuppressWarnings("unchecked")
    private DocBucketResultCache mapBackedCache(boolean enabled) {
        DocBucketResultCache cache = mock(DocBucketResultCache.class);
        when(cache.enabled()).thenReturn(enabled);
        when(cache.bucketKey(any(), anyString(), any(), any(), any(), anyString()))
                .thenAnswer(inv -> {
                    List<Map<String, Object>> blocks = inv.getArgument(0);
                    String model = inv.getArgument(1);
                    return "k:" + blocks.get(0).get("content") + ":" + model;
                });
        when(cache.load(anyString())).thenAnswer(inv -> {
            loads.incrementAndGet();
            return enabled ? bucketStore.get(inv.getArgument(0, String.class)) : null;
        });
        org.mockito.Mockito.doAnswer(inv -> {
            if (enabled) {
                bucketStore.put(inv.getArgument(0, String.class),
                        (List<Map<String, Object>>) inv.getArgument(3));
            }
            return null;
        }).when(cache).store(anyString(), anyLong(), any(), any());
        return cache;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> runOnce(String token, String chapterTwoTail,
                                              DocBucketResultCache cache) {
        return runOnce(token, chapterTwoTail, cache, null);
    }

    private List<Map<String, Object>> runOnce(String token, String chapterTwoTail,
                                              DocBucketResultCache cache,
                                              com.sixbean.testpike.aiservice.doc.domain.component
                                                      .ExtractFidelityLedgerStore fidelityStore) {
        DocExtractTestFixtures fixtures = new DocExtractTestFixtures(DOC_ID, PROJECT_ID, token);
        DocGroupSummaryCache groupCache = mock(DocGroupSummaryCache.class);
        DocExtractServiceImpl service = fixtures.pipeline(groupCache);
        service.setBucketResultCache(cache);
        if (fidelityStore != null) {
            service.setFidelityLedgerStore(fidelityStore);
        }
        when(fixtures.llmRouter().route(PROJECT_ID)).thenReturn(endpoint());
        when(fixtures.coreGateway().getDocumentChunks(DOC_ID)).thenReturn(List.of(
                chunkWithId(101L, 1, "第一章",
                        "字".repeat(99_000) + "会话令牌五分钟过期后必须重新认证"),
                chunkWithId(102L, 2, "第二章", "字".repeat(99_000) + chapterTwoTail)));
        when(fixtures.coreGateway()
                .getSysConfig(com.sixbean.testpike.aiservice.doc.domain.component
                        .ContextBudgetPlanner.KEY_FINAL_BUCKET_MAX_TOKENS)).thenReturn(null);
        String cacheTok = service.cacheToken(token);
        when(groupCache.load(DOC_ID, cacheTok)).thenReturn(null);
        when(groupCache.loadGroups(DOC_ID, cacheTok)).thenReturn(Map.of(
                1, Map.of("seq", 1, "chapter", "第一章", "content", "cached-summary-one"),
                2, Map.of("seq", 2, "chapter", "第二章", "content", "cached-summary-two")));
        when(groupCache.enabled()).thenReturn(true);
        when(groupCache.loadItemGroups(DOC_ID, cacheTok)).thenReturn(Map.of());
        fixtures.stubPlaneTransports(this::reply);

        providerCalls.clear();
        service.extract(fixtures.message());
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(fixtures.coreGateway(), times(1))
                .writeExtractionResult(eq(DOC_ID), captor.capture(), eq(token));
        List<Map<String, Object>> raw = captor.getValue();
        return raw.stream().map(java.util.TreeMap::new).map(m -> (Map<String, Object>) m).toList();
    }

    private static List<String> titlesOf(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> String.valueOf(row.get("title"))).sorted().toList();
    }

    /** Run 1 burns both buckets, run 2 with unchanged content burns NONE and
     *  lands the byte-identical item face, run 3 recomputes only the bucket
     *  whose chapter text changed. */
    @Test
    void 未变桶零外呼且变化桶仅该桶重算() {
        DocBucketResultCache cache = mapBackedCache(true);
        List<Map<String, Object>> first = runOnce("gen-bc-1", "订单退款七个工作日内原路退回", cache);
        assertEquals(2, providerCalls.size(), "run 1 burns both buckets");

        List<Map<String, Object>> second = runOnce("gen-bc-2", "订单退款七个工作日内原路退回", cache);
        assertEquals(0, providerCalls.size(), "unchanged buckets must stay at zero calls");
        assertEquals(titlesOf(first), titlesOf(second), "reuse lands the same item face");
        assertEquals(first, second, "reuse is byte-identical, not just title-equal");

        List<Map<String, Object>> third = runOnce("gen-bc-3", "订单退款十个工作日内原路退回", cache);
        assertEquals(1, providerCalls.size(), "only the changed bucket recomputes");
        assertEquals(titlesOf(first), titlesOf(third));
    }

    /** Switch OFF keeps every run on the legacy full-burn face (zero reuse,
     *  zero stored rows). */
    @Test
    void 关闭态两次运行都全量重算() {
        DocBucketResultCache cache = mapBackedCache(false);
        runOnce("gen-bc-off-1", "订单退款七个工作日内原路退回", cache);
        assertEquals(2, providerCalls.size());
        runOnce("gen-bc-off-2", "订单退款七个工作日内原路退回", cache);
        assertEquals(2, providerCalls.size(), "disabled cache must not reuse");
        assertEquals(0, bucketStore.size(), "disabled cache must not store");
    }

    /** Ticket 1 (reuse-scoped fidelity): the zero-call reuse leg must book
     *  the reused source scope onto the fidelity entry, so the retention
     *  face measures read-or-reused coverage instead of the old 49.80
     *  false red of the live 710db3de generation. */
    @Test
    void reuseLegBooksTheReusedSourceScopeOnTheFidelityEntry() {
        DocBucketResultCache cache = mapBackedCache(true);
        runOnce("gen-rs-warm", "订单退款七个工作日内原路退回", cache);
        com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedgerStore store =
                mock(com.sixbean.testpike.aiservice.doc.domain.component
                        .ExtractFidelityLedgerStore.class);
        runOnce("gen-rs-heat", "订单退款七个工作日内原路退回", cache, store);
        assertEquals(0, providerCalls.size(), "the heat leg burns zero provider calls");
        ArgumentCaptor<com.sixbean.testpike.aiservice.doc.domain.component
                .ExtractFidelityLedger.Entry> captor =
                ArgumentCaptor.forClass(com.sixbean.testpike.aiservice.doc.domain.component
                        .ExtractFidelityLedger.Entry.class);
        verify(store).upsertIfChanged(captor.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                captor.getValue().reusedSourceTokens() > 0,
                "reused buckets must land on the fidelity entry as read-by-reuse scope");
        org.junit.jupiter.api.Assertions.assertTrue(
                captor.getValue().retentionPercent() >= 95.0,
                "a zero-call reuse leg must not read as an unread document");
    }
}
