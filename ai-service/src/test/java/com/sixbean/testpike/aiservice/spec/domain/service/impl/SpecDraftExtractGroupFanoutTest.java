package com.sixbean.testpike.aiservice.spec.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedgerStore;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.GroupParallelFanout;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiSpecExtractMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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
 * wiring the spec contract-extract group loop rides the
 * single-source {@link GroupParallelFanout} (R40: no fourth pool face), driven
 * by the hot key {@code ai.pipeline.group-parallel} read fresh per run:
 * <ul>
 * <li>key "3": group bodies execute on pool worker threads while the
 * written-back draft order stays GROUP order (zero ledger drift — the
 * method+path first-seen merge and the rejected-entry accounting never
 * see a shuffled sequence);</li>
 * <li>key "1": every call stays inline on the caller thread — the serial
 * dual-spectrum baseline spawns no pool at all;</li>
 * <li>fail-closed: an aborting group vocabulary propagates unwrapped and
 * no draft write-back happens for a half-run.</li>
 * </ul>
 *
 * <p>L2 isolation on the same infrastructure seams as the sibling
 * {@link SpecDraftExtractGroupedIntakeTest}: 61 chunks over the legacy cap
 * seal exactly two groups (overlap removed), each group answers a distinct
 * method+path so the landed order is directly observable.
 */
class SpecDraftExtractGroupFanoutTest {

    private static final long DOC_ID = 95L;
    private static final long PROJECT_ID = 35L;
    private static final long OPERATOR_ID = 19L;
    private static final String THREAD_PREFIX = "spec-group-extract";

    /** One distinct interface per group — cross-group merge order and the
     *  per-group thread face both become mechanically observable. */
    private static final String JSON_GROUP_ONE = """
            {"items":[{"method":"POST","path":"/api/v1/orders","title":"创建订单",\
            "sourceAnchor":"创建订单接口 POST /api/v1/orders 片段1"}]}""";
    private static final String JSON_GROUP_TWO = """
            {"items":[{"method":"GET","path":"/api/v1/refunds","title":"退款查询",\
            "sourceAnchor":"创建订单接口 POST /api/v1/orders 片段46"}]}""";

    private final String callerThread = Thread.currentThread().getName();
    private final List<String> groupCallThreads = new CopyOnWriteArrayList<>();
    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final ExtractFidelityLedgerStore fidelityStore =
            mock(ExtractFidelityLedgerStore.class);
    private final SpecDraftExtractServiceImpl service = new SpecDraftExtractServiceImpl(
            coreGateway, llmChain, llmRouter, new ObjectMapper(), fidelityStore);

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG);
    }

    private static AiSpecExtractMessage message() {
        return new AiSpecExtractMessage(DOC_ID, PROJECT_ID, OPERATOR_ID, null, "pub-token-3");
    }

    private static Map<String, Object> chunk(int seq) {
        return Map.of("seq", seq, "chapter", "接口清单",
                "content", "创建订单接口 POST /api/v1/orders 片段" + seq);
    }

    /** Two-group fixture: 61 chunks, overlap removed, the fan-out key pinned
     *  to the given raw value; group 2 is recognised by chunk 46. */
    private void stubRun(String rawKey) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (int i = 1; i <= 61; i++) {
            chunks.add(chunk(i));
        }
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(chunks);
        when(coreGateway.getSysConfig("apitest.spec-extract.group-overlap-ratio"))
                .thenReturn("0");
        when(coreGateway.getSysConfig(GroupParallelFanout.KEY_GROUP_PARALLEL))
                .thenReturn(rawKey);
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenAnswer(invocation -> {
                    ChatCompletionRequest request = invocation.getArgument(0);
                    groupCallThreads.add(Thread.currentThread().getName());
                    boolean secondGroup = request.messages().stream()
                            .anyMatch(m -> m.content() != null && m.content().contains("片段46"));
                    return new LlmChatResult(secondGroup ? JSON_GROUP_TWO : JSON_GROUP_ONE,
                            "openai", "routed-model", 100, 50, 20L);
                });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> runAndCaptureDrafts() {
        service.extract(message());
        ArgumentCaptor<List<Map<String, Object>>> written =
                ArgumentCaptor.forClass(List.class);
        verify(coreGateway).writeSpecDrafts(eq(DOC_ID), eq(OPERATOR_ID), written.capture());
        return written.getValue();
    }

    /** Key 3: group bodies ride the shared pool, yet the written-back order
     *  stays group order — parallelism must not leak into the merge. */
    @Test
    void keyThree_groupBodiesOnPoolThreads_writtenOrderStaysGroupOrder() {
        stubRun("3");

        List<Map<String, Object>> drafts = runAndCaptureDrafts();

        assertEquals(List.of("/api/v1/orders", "/api/v1/refunds"),
                drafts.stream().map(row -> row.get("path")).toList());
        assertEquals(2, groupCallThreads.size(), "exactly one grouped call per group");
        assertTrue(groupCallThreads.stream().allMatch(name -> name.startsWith(THREAD_PREFIX)),
                () -> "group bodies must run on pool workers, saw " + groupCallThreads);
    }

    /** Key 1: the serial baseline spawns no pool — every call stays inline
     *  on the caller thread with the identical landed order. */
    @Test
    void keyOne_serialBaseline_staysInlineOnCallerThread() {
        stubRun("1");

        List<Map<String, Object>> drafts = runAndCaptureDrafts();

        assertEquals(List.of("/api/v1/orders", "/api/v1/refunds"),
                drafts.stream().map(row -> row.get("path")).toList());
        assertEquals(2, groupCallThreads.size());
        assertTrue(groupCallThreads.stream().allMatch(callerThread::equals),
                () -> "serial spectrum must never leave the caller thread, saw "
                        + groupCallThreads);
    }

    /** three-group fixture (91 chunks seal exactly three
 * groups on the intake planner) with the fan-out key pinned to 2: the
 * second group dies on a provider timeout while the first group is
 * still streaming. The shared run signal must keep the never-started
 * third group away from the provider (gate-after call increment 0) and
 * the run must end on the honest timeout attribution with zero
 * write-back. */
    @Test
    void timeoutGroupTripsRunSignal_neverStartedSiblingBurnsNoProviderCall() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (int i = 1; i <= 90; i++) {
            chunks.add(chunk(i));
        }
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(chunks);
        when(coreGateway.getSysConfig("apitest.spec-extract.group-overlap-ratio"))
                .thenReturn("0");
        when(coreGateway.getSysConfig("apitest.spec-extract.group-max-chunks"))
                .thenReturn("30");
        when(coreGateway.getSysConfig(GroupParallelFanout.KEY_GROUP_PARALLEL))
                .thenReturn("2");
        java.util.concurrent.atomic.AtomicInteger thirdGroupCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenAnswer(invocation -> {
                    ChatCompletionRequest request = invocation.getArgument(0);
                    String prompt = request.messages().stream()
                            .map(ChatMessage::content)
                            .filter(java.util.Objects::nonNull)
                            .reduce("", (a, b) -> a + "\n" + b);
                    if (prompt.contains("片段46")) {
                        throw new ServiceException("模型服务调用超时，本组未完成",
                                BizErrorCode.AI_TIMEOUT);
                    }
                    if (prompt.contains("片段76")) {
                        thirdGroupCalls.incrementAndGet();
                        return new LlmChatResult(JSON_GROUP_TWO, "openai", "routed-model",
                                100, 50, 20L);
                    }
                    Thread.sleep(1_500);
                    return new LlmChatResult(JSON_GROUP_ONE, "openai", "routed-model",
                            100, 50, 20L);
                });

        ServiceException refused = assertThrows(ServiceException.class,
                () -> service.extract(message()));

        assertEquals(0, thirdGroupCalls.get(),
                "a tripped run must never launch the unstarted third group");
        assertEquals(BizErrorCode.AI_TIMEOUT, refused.getCode(),
                "the gate-stop must attribute as timeout, not format error");
        assertTrue(refused.getMessage().contains("已停止未开始的分组"),
                () -> "honest time-budget terminal wording expected, saw "
                        + refused.getMessage());
        verify(coreGateway, never()).writeSpecDrafts(anyLong(), anyLong(), any());
    }

    /** Negative drift pin for the same seam: a NON-timeout group failure
     *  never trips the run signal — every submitted group still reaches the
     *  provider and the original fail-closed vocabulary propagates unwrapped
     *  (zero behaviour change for the closed spectrum). */
    @Test
    void nonTimeoutFailure_keepsSiblingsRunningAndOriginalVocabulary() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (int i = 1; i <= 90; i++) {
            chunks.add(chunk(i));
        }
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(chunks);
        when(coreGateway.getSysConfig("apitest.spec-extract.group-overlap-ratio"))
                .thenReturn("0");
        when(coreGateway.getSysConfig("apitest.spec-extract.group-max-chunks"))
                .thenReturn("30");
        when(coreGateway.getSysConfig(GroupParallelFanout.KEY_GROUP_PARALLEL))
                .thenReturn("2");
        java.util.concurrent.atomic.AtomicInteger providerCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenAnswer(invocation -> {
                    ChatCompletionRequest request = invocation.getArgument(0);
                    providerCalls.incrementAndGet();
                    String prompt = request.messages().stream()
                            .map(ChatMessage::content)
                            .filter(java.util.Objects::nonNull)
                            .reduce("", (a, b) -> a + "\n" + b);
                    if (prompt.contains("片段46")) {
                        throw new ServiceException("模型服务连接失败，无法完成接口抽取",
                                BizErrorCode.AI_FORMAT_ERROR);
                    }
                    Thread.sleep(300);
                    return new LlmChatResult(prompt.contains("片段76")
                            ? JSON_GROUP_TWO : JSON_GROUP_ONE,
                            "openai", "routed-model", 100, 50, 20L);
                });

        ServiceException refused = assertThrows(ServiceException.class,
                () -> service.extract(message()));

        assertTrue(refused.getMessage().contains("模型服务连接失败"),
                () -> "non-timeout abort vocabulary stays unwrapped, saw "
                        + refused.getMessage());
        assertEquals(3, providerCalls.get(),
                "an untripped signal must not cancel any submitted sibling");
        verify(coreGateway, never()).writeSpecDrafts(anyLong(), anyLong(), any());
    }

    /** The K8 face is read fresh exactly once per run (uncached), and an
     *  aborting provider vocabulary propagates unwrapped with zero write-back
     *  — a half-run never lands drafts. */
    @Test
    void keyReadOncePerRun_andAbortingGroupFailsClosedWithoutWriteBack() {
        stubRun("3");
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenThrow(new ServiceException("模型服务连接失败，无法完成接口抽取",
                        BizErrorCode.AI_FORMAT_ERROR));

        ServiceException refused = assertThrows(ServiceException.class,
                () -> service.extract(message()));

        assertTrue(refused.getMessage().contains("模型服务连接失败"),
                "abort vocabulary stays recognisable upstream, saw " + refused.getMessage());
        verify(coreGateway, times(1))
                .getSysConfig(GroupParallelFanout.KEY_GROUP_PARALLEL);
        verify(coreGateway, never()).writeSpecDrafts(anyLong(), anyLong(), any());
    }
}
