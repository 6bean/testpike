package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.sixbean.testpike.aiservice.doc.domain.component.DocGroupSummaryCache;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.chunkWithId;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.endpoint;

/**
 * grouped raw plane long-tail removal. A group whose
 * provider attempt fails converges its INLINE retries to the hot key
 * {@code ai.pipeline.group-inline-retry-limit} (factory default 1 — the
 * worst-case ledger, p90 224.6s/max 901.6s per call, is what pins
 * the bound), then the group is removed from the hot loop and re-submitted
 * in a bounded deferred round ({@code ai.pipeline.group-deferred-rounds},
 * default 1). Sibling groups are never re-burned by the deferred round: the
 * per-group item cache resumes what already landed. Fail-closed invariants
 * are untouched: a superseded generation never reads as a removable long
 * tail, and a group still failing after every bound aborts the whole run
 * with the honest wording (partial item sets are never written back).
 *
 * <p>Spectrum shape shared with the sibling fan-out test: two 100k-char
 * chapter chunks → exactly two groups, BOTH group summaries cached and zero
 * item cache, so every provider call belongs to one named raw group.
 * {@code ai.pipeline.group-parallel}=1 keeps counting deterministic —
 * deferral is orthogonal to parallelism (the fan-out contract already owns
 * the pool face).
 */
class DocExtractGroupLongTailDeferralTest {

    private static final long DOC_ID = 99L;
    private static final long PROJECT_ID = 39L;
    private static final String TOKEN = "gen-token-deferral";

    private static final String JSON_GROUP_ONE = """
            {"requirements":[{"title":"令牌过期策略","acceptanceCriteria":"令牌五分钟后强制重新认证",\
             "chapter":"第一章","confidence":0.9,"evidence":"会话令牌五分钟过期"}]}""";
    private static final String JSON_GROUP_TWO = """
            {"requirements":[{"title":"订单退款时效","acceptanceCriteria":"退款七个工作日内原路退回",\
             "chapter":"第二章","confidence":0.9,"evidence":"订单退款七个工作日内原路退回"}]}""";

    private final AtomicInteger groupOneCalls = new AtomicInteger();
    private final AtomicInteger groupTwoCalls = new AtomicInteger();

    /** Factory-default K8 face: both new keys blank (garbage/absent reads must
     *  fall back to the same defaults, asserted by the bounded-attempt counts),
     *  serial fan-out. */
    private CoreGateway factoryDefaultKeys() {
        DocExtractTestFixtures fixtures = new DocExtractTestFixtures(DOC_ID, PROJECT_ID, TOKEN);
        return fixtures.coreGateway();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> landedItems(CoreGateway gateway) {
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(gateway).writeExtractionResult(eq(DOC_ID), captor.capture(), eq(TOKEN));
        return captor.getValue();
    }

    private void transport(DocExtractTestFixtures fixtures,
                           java.util.function.Function<ChatCompletionRequest, LlmChatResult> face) {
        fixtures.stubPlaneTransports(face);
    }

    /** Group-attributed provider face: group one trips AI_TIMEOUT on the
     *  call ordinals listed in tripGroupOne, every other call succeeds. */
    private LlmChatResult groupTimedReply(ChatCompletionRequest request, int... tripGroupOne) {
        boolean groupOne = request.messages().get(1).content().contains("会话令牌");
        int hit = (groupOne ? groupOneCalls : groupTwoCalls).incrementAndGet();
        if (groupOne) {
            for (int ordinal : tripGroupOne) {
                if (hit == ordinal) {
                    throw new ServiceException("模型调用超时", BizErrorCode.AI_TIMEOUT);
                }
            }
            return new LlmChatResult(JSON_GROUP_ONE, "openai", "routed-model", 100, 50, 20L);
        }
        return new LlmChatResult(JSON_GROUP_TWO, "openai", "routed-model", 100, 50, 20L);
    }

    private DocExtractTestFixtures spectrum(DocGroupSummaryCache cache) {
        DocExtractTestFixtures fixtures =
                new DocExtractTestFixtures(DOC_ID, PROJECT_ID, TOKEN);
        CoreGateway gateway = fixtures.coreGateway();
        // The bounded streams each ride EXACTLY ONE provider call per attempt:
        // the streaming face would double-count (a stream-establishment
        // failure falls back to non-stream inside the same attempt — the
        // resilience seam), so the deferral spectrum pins the non-stream
        // channel and keeps the attempt ledger of the removal bound precisely
        // observable.
        when(gateway.getSysConfig(DocExtractServiceImpl.KEY_EXTRACT_STREAM_ENABLED))
                .thenReturn("false");
        when(fixtures.llmRouter().route(PROJECT_ID)).thenReturn(endpoint());
        when(gateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                chunkWithId(101L, 1, "第一章",
                        "字".repeat(99_000) + "会话令牌五分钟过期后必须重新认证"),
                chunkWithId(102L, 2, "第二章",
                        "字".repeat(99_000) + "订单退款七个工作日内原路退回")));
        when(cache.enabled()).thenReturn(true);
        when(cache.load(eq(DOC_ID), any())).thenReturn(null);
        when(cache.loadGroups(eq(DOC_ID), any())).thenReturn(Map.of(
                1, Map.of("seq", 1, "chapter", "第一章", "content", "cached-summary-one"),
                2, Map.of("seq", 2, "chapter", "第二章", "content", "cached-summary-two")));
        when(cache.loadItemGroups(eq(DOC_ID), any())).thenReturn(Map.of());
        return fixtures;
    }

    /** Inline healing: the single retry bound suffices — group one trips its
     *  first call only. Nothing is deferred, the sibling burns exactly one
     *  call (zero cross-group pollution), both items land in group order. */
    @Test
    void 首次超时由内联单次重试自愈且邻组零重复燃烧() {
        DocGroupSummaryCache cache = mock(DocGroupSummaryCache.class);
        DocExtractTestFixtures fixtures = spectrum(cache);
        transport(fixtures, request -> groupTimedReply(request, 1));

        fixtures.pipeline(cache).extract(fixtures.message());

        List<Map<String, Object>> landed = landedItems(fixtures.coreGateway());
        assertEquals(List.of("令牌过期策略", "订单退款时效"),
                landed.stream().map(row -> row.get("title")).toList());
        assertEquals(2, groupOneCalls.get(), "first attempt + the single inline retry");
        assertEquals(1, groupTwoCalls.get(), "sibling group never touched by the healed group");
    }

    /** Removal: two inline failures (first pass + the converged single retry)
     *  take group one out of the hot loop; the deferred round re-submits ONLY
     *  that group and heals on its third call. The sibling keeps its landed
     *  items untouched, the folded order stays group order. */
    @Test
    void 双败后摘入延后重提轮仅重烧被摘组() {
        DocGroupSummaryCache cache = mock(DocGroupSummaryCache.class);
        DocExtractTestFixtures fixtures = spectrum(cache);
        transport(fixtures, request -> groupTimedReply(request, 1, 2));
        when(cache.loadItemGroups(eq(DOC_ID), any())).thenReturn(Map.of());

        fixtures.pipeline(cache).extract(fixtures.message());

        List<Map<String, Object>> landed = landedItems(fixtures.coreGateway());
        assertEquals(List.of("令牌过期策略", "订单退款时效"),
                landed.stream().map(row -> row.get("title")).toList());
        assertEquals(3, groupOneCalls.get(),
                "two inline attempts this round + exactly one deferred re-submission");
        assertEquals(1, groupTwoCalls.get(),
                "the deferred round resumes the sibling from the item cache — zero re-burn");
    }

    /** Honesty bound: a group failing past every bound aborts the whole run
     *  with wording that names the removed group; nothing partial is ever
     *  written back. Attempt count proves the bound is convergent
     *  (1 inline pass + retry 1 + deferred round 1 = 3, never a fourth). */
    @Test
    void 越界仍败则整轮诚实中止零回写() {
        DocGroupSummaryCache cache = mock(DocGroupSummaryCache.class);
        DocExtractTestFixtures fixtures = spectrum(cache);
        transport(fixtures, request -> groupTimedReply(request, 1, 2, 3, 4, 5));

        CoreGateway gateway = fixtures.coreGateway();
        ServiceException abort = assertThrows(ServiceException.class,
                () -> fixtures.pipeline(cache).extract(fixtures.message()));
        assertTrue(abort.getMessage().contains("第 1/2 组"),
                "the honest wording names the removed group: " + abort.getMessage());
        assertEquals(3, groupOneCalls.get(), "the converged bound — no fourth burn");
        verify(gateway, never()).writeExtractionResult(eq(DOC_ID), any(), eq(TOKEN));
    }

    /** Fail-closed vocabulary: a superseded generation is NEVER reinterpreted
     *  as a removable long tail — the run stops with the quiet honest return
     *  and zero provider burn. */
    @Test
    void 换代中止永不判为长尾摘除() {
        DocGroupSummaryCache cache = mock(DocGroupSummaryCache.class);
        DocExtractTestFixtures fixtures = spectrum(cache);
        transport(fixtures, request -> groupTimedReply(request));
        when(fixtures.coreGateway().fetchExtractToken(DOC_ID)).thenReturn("a-newer-generation");

        fixtures.pipeline(cache).extract(fixtures.message());

        assertEquals(0, groupOneCalls.get() + groupTwoCalls.get(),
                "a superseded run never reaches the provider through the deferral wrapper");
        verify(fixtures.coreGateway(), never())
                .writeExtractionResult(eq(DOC_ID), any(), eq(TOKEN));
    }
}
