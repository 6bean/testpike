package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.sixbean.testpike.aiservice.doc.domain.component.DocGroupSummaryCache;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.GroupParallelFanout;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.chunkWithId;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.endpoint;

/**
 * segment — the F5 Map summarisation plane of DocExtractServiceImpl
 * rides the single-source {@link GroupParallelFanout} (R40 — the shape is
 * the grouped raw wiring of commit db6c0a802, no fourth pool face and no
 * second implementation). The live window's gap owner was this plane: its
 * provider calls all landed serially on the MQ consumer thread while the
 * extraction plane idled, so the fan-out here is the fix under test.
 * <ul>
 * <li>key "3": the uncached summary calls overlap on pool workers while
 * the overview blocks stay GROUP order (zero ledger drift — stage
 * rows and the saved round replay strictly in group order);</li>
 * <li>key "1": every call stays inline on the caller thread — the serial
 * dual-spectrum baseline spawns no pool at all;</li>
 * <li>a partially resumed round skips the cached group's burn entirely
 * (the resume granularity survives the wiring);</li>
 * <li>the K8 key reads fresh per round when a burn is pending, and a
 * fully resumed round (every group cached) spawns nothing, burns
 * nothing and reads the key not at all.</li>
 * </ul>
 *
 * <p>L2 isolation on the same infrastructure seams as the sibling raw-plane
 * fan-out test (CoreGateway door, LlmFallbackChain provider, ProjectLlmRouter,
 * cache). Two 100k-char chapter chunks group into exactly two groups; the
 * item cache stays empty so the raw plane burns its own two calls (already
 * parallel since db6c0a802, provider replies keyed by content so no stub
 * order assumption leaks in), and distinct titles keep the merge-ring at
 * zero burn.
 */
class DocExtractSummaryPlaneFanoutTest {

    private static final long DOC_ID = 101L;
    private static final long PROJECT_ID = 41L;
    private static final String THREAD_PREFIX = "doc-group-summary";

    /** One verbatim-attributable excerpt per group — same pair the sibling
     *  raw-plane test pins, so the landed item order stays observable. */
    private static final String JSON_GROUP_ONE = """
            {"requirements":[{"title":"令牌过期策略","acceptanceCriteria":"令牌五分钟后强制重新认证",\
             "chapter":"第一章","confidence":0.9,"evidence":"会话令牌五分钟过期"}]}""";
    private static final String JSON_GROUP_TWO = """
            {"requirements":[{"title":"订单退款时效","acceptanceCriteria":"退款七个工作日内原路退回",\
             "chapter":"第二章","confidence":0.9,"evidence":"订单退款七个工作日内原路退回"}]}""";

    private final String callerThread = Thread.currentThread().getName();
    private final List<String> summaryPlaneThreads = new CopyOnWriteArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private CoreGateway lastCoreGateway;
    private DocGroupSummaryCache lastCache;
    private String lastCacheTok;
    private List<Map<String, Object>> lastOverview = List.of();

    private LlmChatResult reply(ChatCompletionRequest request) {
        String system = request.messages().get(0).content();
        if (!system.contains(DocExtractServiceImpl.SUMMARY_MARKER)) {
            boolean firstGroup = request.messages().stream()
                    .anyMatch(m -> m.content() != null && m.content().contains("会话令牌"));
            return new LlmChatResult(firstGroup ? JSON_GROUP_ONE : JSON_GROUP_TWO,
                    "openai", "routed-model", 100, 50, 20L);
        }
        summaryPlaneThreads.add(Thread.currentThread().getName());
        int concurrent = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(concurrent, Math::max);
        try {
            Thread.sleep(200);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        inFlight.decrementAndGet();
        boolean firstGroup = request.messages().get(1).content().contains("会话令牌");
        return new LlmChatResult(firstGroup ? "第一章概览文本" : "第二章概览文本",
                "openai", "routed-model", 10, 5, 1L);
    }

    /** One full spectrum run under the given raw K8 value and the given
     *  per-group summary cache face. Stores the overview the whole-round
     *  seam saved and returns the landed item rows exactly as the write-back
     *  door received them. */
    private List<Map<String, Object>> runSpectrum(String rawKey,
                                                  Map<Integer, Map<String, Object>> cachedSummaries) {
        String token = "gen-token-summary-fanout-" + rawKey + "-" + cachedSummaries.size();
        DocExtractTestFixtures fixtures = new DocExtractTestFixtures(DOC_ID, PROJECT_ID, token);
        DocGroupSummaryCache cache = mock(DocGroupSummaryCache.class);
        DocExtractServiceImpl service = fixtures.pipeline(cache);
        lastCoreGateway = fixtures.coreGateway();
        lastCache = cache;
        when(fixtures.llmRouter().route(PROJECT_ID)).thenReturn(endpoint());
        when(lastCoreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                chunkWithId(111L, 1, "第一章",
                        "字".repeat(99_000) + "会话令牌五分钟过期后必须重新认证"),
                chunkWithId(112L, 2, "第二章",
                        "字".repeat(99_000) + "订单退款七个工作日内原路退回")));
        when(lastCoreGateway.getSysConfig(GroupParallelFanout.KEY_GROUP_PARALLEL))
                .thenReturn(rawKey);
        lastCacheTok = service.cacheToken(token);
        when(cache.load(DOC_ID, lastCacheTok)).thenReturn(null);
        when(cache.loadGroups(DOC_ID, lastCacheTok)).thenReturn(cachedSummaries);
        when(cache.enabled()).thenReturn(true);
        when(cache.loadItemGroups(DOC_ID, lastCacheTok)).thenReturn(Map.of());
        fixtures.stubPlaneTransports(this::reply);

        service.extract(fixtures.message());
        ArgumentCaptor<List<Map<String, Object>>> landedCaptor = ArgumentCaptor.forClass(List.class);
        verify(lastCoreGateway).writeExtractionResult(eq(DOC_ID), landedCaptor.capture(), eq(token));
        ArgumentCaptor<List<Map<String, Object>>> overviewCaptor = ArgumentCaptor.forClass(List.class);
        verify(cache).save(eq(DOC_ID), eq(lastCacheTok), overviewCaptor.capture());
        lastOverview = overviewCaptor.getValue();
        return landedCaptor.getValue();
    }

    /** Key 3: the summary bodies ride the shared pool, their calls overlap,
     *  yet the overview blocks stay group order — parallelism must not leak
     *  into the ledger. */
    @Test
    void 键三摘要外呼重叠运行且概览保持组序() {
        List<Map<String, Object>> landed = runSpectrum("3", Map.of());
        assertEquals(List.of("令牌过期策略", "订单退款时效"),
                landed.stream().map(row -> row.get("title")).toList());
        assertEquals(2, summaryPlaneThreads.size(), "exactly one summary call per group");
        assertTrue(summaryPlaneThreads.stream().allMatch(name -> name.startsWith(THREAD_PREFIX)),
                () -> "summary bodies must run on pool workers, saw " + summaryPlaneThreads);
        assertTrue(maxInFlight.get() >= 2,
                () -> "the two summary calls must be in flight at once, max saw "
                        + maxInFlight.get());
        assertEquals(List.of("第一章概览文本", "第二章概览文本"),
                lastOverview.stream().map(row -> String.valueOf(row.get("content"))).toList());
    }

    /** Key 1: the serial spectrum spawns nothing — every summary call is
     *  inline on the caller thread with the identical overview order. */
    @Test
    void 键一摘要外呼全内联零池线程且概览顺序不变() {
        List<Map<String, Object>> landed = runSpectrum("1", Map.of());
        assertEquals(List.of("令牌过期策略", "订单退款时效"),
                landed.stream().map(row -> row.get("title")).toList());
        assertEquals(2, summaryPlaneThreads.size());
        assertTrue(summaryPlaneThreads.stream().allMatch(callerThread::equals),
                () -> "parallel<=1 must stay inline, saw " + summaryPlaneThreads);
        assertEquals(1, maxInFlight.get(), "serial must never overlap two summary calls");
        assertEquals(List.of("第一章概览文本", "第二章概览文本"),
                lastOverview.stream().map(row -> String.valueOf(row.get("content"))).toList());
    }

    /** The resume granularity survives: a cached group burns no call
 * and saves no group row, the pending sibling still computes, and the
 * overview keeps strict group order across both faces. */
    @Test
    void 缓存续跑混合态首组禁重烧残组照常且概览保序() {
        runSpectrum("3", Map.of(
                1, Map.of("seq", 1, "chapter", "第一章", "content", "cached-summary-one")));
        assertEquals(1, summaryPlaneThreads.size(), "the cached group must not re-burn");
        assertEquals(List.of("cached-summary-one", "第二章概览文本"),
                lastOverview.stream().map(row -> String.valueOf(row.get("content"))).toList());
        verify(lastCache, times(1)).saveGroup(eq(DOC_ID), eq(lastCacheTok), eq(2), any());
        verify(lastCache, never()).saveGroup(eq(DOC_ID), eq(lastCacheTok), eq(1), any());
    }

    /** Fresh K8 read per round with a burn pending: one read for the
     *  summary plane plus the grouped raw plane's own (db6c0a802). A fully
     *  resumed round must not add a third read — pinned by the sibling
     *  raw-plane test's times(1) assertion. */
    @Test
    void 每轮待烧时现读组并行键() {
        runSpectrum("3", Map.of());
        verify(lastCoreGateway, times(2))
                .getSysConfig(GroupParallelFanout.KEY_GROUP_PARALLEL);
    }
}
