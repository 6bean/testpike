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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.chunkWithId;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.endpoint;

/**
 * wiring the grouped RAW plane of DocExtractServiceImpl now
 * rides the single-source {@link GroupParallelFanout} (R40: no fourth pool
 * face), driven by the hot key {@code ai.pipeline.group-parallel} read fresh
 * per run:
 * <ul>
 * <li>key "3": group task bodies execute on pool worker threads while the
 * landed item order stays GROUP order (zero ledger drift — the
 * first-seen fold and the stage face never see a shuffled sequence);</li>
 * <li>key "1": every call stays inline on the caller thread — the serial
 * dual-spectrum baseline spawns no pool at all.</li>
 * </ul>
 *
 * <p>L2 isolation on the same infrastructure seams as the sibling
 * grouped-raw resume test (CoreGateway door, LlmFallbackChain provider,
 * ProjectLlmRouter, cache). Two 100k-char chapter chunks group into exactly
 * two groups with BOTH group summaries cached and no item cache, so the whole
 * run burns exactly two raw plane calls; distinct titles mean zero merge-ring
 * burn and a directly observable landed order.
 */
class DocExtractGroupPlaneFanoutTest {

    private static final long DOC_ID = 99L;
    private static final long PROJECT_ID = 39L;
    private static final String THREAD_PREFIX = "doc-group-extract";

    /** One verbatim-attributable excerpt per group — distinct titles keep the
     *  cross-group fold (and therefore the whole landed order) observable. */
    private static final String JSON_GROUP_ONE = """
            {"requirements":[{"title":"令牌过期策略","acceptanceCriteria":"令牌五分钟后强制重新认证",\
             "chapter":"第一章","confidence":0.9,"evidence":"会话令牌五分钟过期"}]}""";
    private static final String JSON_GROUP_TWO = """
            {"requirements":[{"title":"订单退款时效","acceptanceCriteria":"退款七个工作日内原路退回",\
             "chapter":"第二章","confidence":0.9,"evidence":"订单退款七个工作日内原路退回"}]}""";

    private final String callerThread = Thread.currentThread().getName();
    private final List<String> rawPlaneThreads = new CopyOnWriteArrayList<>();
    private CoreGateway lastCoreGateway;

    private LlmChatResult reply(ChatCompletionRequest request) {
        String system = request.messages().get(0).content();
        if (system.contains(DocExtractServiceImpl.SUMMARY_MARKER)) {
            return new LlmChatResult("转述概览文本", "openai", "routed-model", 10, 5, 1L);
        }
        rawPlaneThreads.add(Thread.currentThread().getName());
        boolean firstGroup = request.messages().stream()
                .anyMatch(m -> m.content() != null && m.content().contains("会话令牌"));
        return new LlmChatResult(firstGroup ? JSON_GROUP_ONE : JSON_GROUP_TWO,
                "openai", "routed-model", 100, 50, 20L);
    }

    /** One full spectrum run under the given raw K8 value: over-budget
     *  two-chapter document, both group summaries cached, zero item cache —
     *  every provider call belongs to the grouped raw plane. Returns the
     *  landed item rows exactly as the write-back door received them. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> runSpectrum(String rawKey) {
        String token = "gen-token-fanout-" + rawKey;
        DocExtractTestFixtures fixtures = new DocExtractTestFixtures(DOC_ID, PROJECT_ID, token);
        DocGroupSummaryCache cache = mock(DocGroupSummaryCache.class);
        DocExtractServiceImpl service = fixtures.pipeline(cache);
        lastCoreGateway = fixtures.coreGateway();
        when(fixtures.llmRouter().route(PROJECT_ID)).thenReturn(endpoint());
        when(lastCoreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                chunkWithId(101L, 1, "第一章",
                        "字".repeat(99_000) + "会话令牌五分钟过期后必须重新认证"),
                chunkWithId(102L, 2, "第二章",
                        "字".repeat(99_000) + "订单退款七个工作日内原路退回")));
        when(lastCoreGateway.getSysConfig(GroupParallelFanout.KEY_GROUP_PARALLEL))
                .thenReturn(rawKey);
        String cacheTok = service.cacheToken(token);
        when(cache.load(DOC_ID, cacheTok)).thenReturn(null);
        when(cache.loadGroups(DOC_ID, cacheTok)).thenReturn(Map.of(
                1, Map.of("seq", 1, "chapter", "第一章", "content", "cached-summary-one"),
                2, Map.of("seq", 2, "chapter", "第二章", "content", "cached-summary-two")));
        when(cache.enabled()).thenReturn(true);
        when(cache.loadItemGroups(DOC_ID, cacheTok)).thenReturn(Map.of());
        fixtures.stubPlaneTransports(this::reply);

        service.extract(fixtures.message());
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(lastCoreGateway).writeExtractionResult(eq(DOC_ID), captor.capture(), eq(token));
        return captor.getValue();
    }

    /** Key 3: group bodies ride the shared pool, yet the landed order stays
     *  group order — parallelism must not leak into the ledger. */
    @Test
    void 键三组任务跑在池线程且落地保持组序() {
        List<Map<String, Object>> landed = runSpectrum("3");
        assertEquals(List.of("令牌过期策略", "订单退款时效"),
                landed.stream().map(row -> row.get("title")).toList());
        assertEquals(2, rawPlaneThreads.size(), "exactly one raw call per group");
        assertTrue(rawPlaneThreads.stream().allMatch(name -> name.startsWith(THREAD_PREFIX)),
                () -> "group bodies must run on pool workers, saw " + rawPlaneThreads);
    }

    /** Key 1: the serial spectrum spawns nothing — every call is inline on
     *  the caller thread with the identical landed order. */
    @Test
    void 键一全内联零池线程且落地顺序不变() {
        List<Map<String, Object>> landed = runSpectrum("1");
        assertEquals(List.of("令牌过期策略", "订单退款时效"),
                landed.stream().map(row -> row.get("title")).toList());
        assertEquals(2, rawPlaneThreads.size());
        assertTrue(rawPlaneThreads.stream().allMatch(callerThread::equals),
                () -> "parallel<=1 must stay inline, saw " + rawPlaneThreads);
    }

    /** The K8 face reads fresh per run — one getSysConfig hit per extract. */
    @Test
    void 每次运行现读组并行键一次() {
        runSpectrum("3");
        verify(lastCoreGateway, times(1)).getSysConfig(GroupParallelFanout.KEY_GROUP_PARALLEL);
    }
}
