package com.sixbean.testpike.aiservice.judge.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedgerStore;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenJobView;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenRequest;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenSubmitView;
import com.sixbean.testpike.aiservice.judge.domain.component.QaGenJobStore;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.GroupParallelFanout;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * wiring the background QA-generation job ( job
 * plane, previously a strictly serial group walk on one single-thread pool)
 * rides the single-source {@link GroupParallelFanout} for its group calls
 * (R40: no fourth pool face), driven by the hot key
 * {@code ai.pipeline.group-parallel} read fresh per run:
 * <ul>
 * <li>key "3": group calls execute on pool workers named
 * {@code qa-gen-group-<docId>-N} while the cumulative landing face is
 * replayed strictly in GROUP order — done-groups count up 1..N, the
 * merged item order never shuffles, step events stay honest;</li>
 * <li>key "1": the serial baseline spawns no fan-out pool — every group
 * call stays inline on the job launcher thread;</li>
 * <li>fail-closed: a later group aborting fail-fast lands ZERO cumulative
 * rows (the collect-then-fold order means a half-run can never leak
 * partial items into the job row) and closes the run failed with the
 * unwrapped human cause.</li>
 * </ul>
 *
 * <p>L2 isolation on the same seams as the sibling
 * {@link EvalQuestionGenJobPlaneTest}: 10 chunks sealed into exactly two
 * groups (chunk ceiling 5, overlap removed) over the sync cap 1, so the
 * submit face takes the job path.
 */
class EvalQuestionGenJobFanoutTest {

    private static final long DOC_ID = 96L;
    private static final long PROJECT_ID = 36L;
    private static final long OPERATOR_ID = 20L;
    private static final String JOB_KIND = "qa-gen";
    private static final String POOL_PREFIX = "qa-gen-group-" + DOC_ID;

    private static final String JSON_GROUP_ONE = """
            {"items":[{"question":"订单创建接口用什么方法？","answer":"POST /api/v1/orders",\
            "sourceAnchor":"创建订单接口 POST /api/v1/orders 事实片段1"}]}""";
    private static final String JSON_GROUP_TWO = """
            {"items":[{"question":"退款查询接口用什么方法？","answer":"GET /api/v1/refunds",\
            "sourceAnchor":"创建订单接口 POST /api/v1/orders 事实片段6"}]}""";

    private final List<String> groupCallThreads = new CopyOnWriteArrayList<>();
    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final ExtractFidelityLedgerStore fidelityStore =
            mock(ExtractFidelityLedgerStore.class);
    private final QaGenJobStore jobStore = mock(QaGenJobStore.class);
    private final TaskProcessEventStore processEvents = mock(TaskProcessEventStore.class);
    private final EvalQuestionGenServiceImpl service = new EvalQuestionGenServiceImpl(
            coreGateway, llmChain, llmRouter, new ObjectMapper(), fidelityStore, null,
            jobStore, processEvents);

    private final AtomicReference<QaGenJobView> jobFace = new AtomicReference<>();

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG);
    }

    private static QaGenRequest request() {
        return new QaGenRequest(PROJECT_ID, DOC_ID, null, OPERATOR_ID);
    }

    /** Two-group fixture over the sync cap: group 2 recognised by chunk 6. */
    private void stubRun(String rawKey) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            chunks.add(Map.of("seq", i, "chapter", "验收标准",
                    "content", "创建订单接口 POST /api/v1/orders 事实片段" + i));
        }
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(chunks);
        when(coreGateway.getSysConfig("judge.qa-gen.group-overlap-ratio")).thenReturn("0");
        when(coreGateway.getSysConfig("judge.qa-gen.group-max-chunks")).thenReturn("5");
        when(coreGateway.getSysConfig(GroupParallelFanout.KEY_GROUP_PARALLEL))
                .thenReturn(rawKey);
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenAnswer(invocation -> {
                    ChatCompletionRequest req = invocation.getArgument(0);
                    groupCallThreads.add(Thread.currentThread().getName());
                    boolean secondGroup = req.messages().stream()
                            .anyMatch(m -> m.content() != null
                                    && m.content().contains("事实片段6"));
                    return new LlmChatResult(secondGroup ? JSON_GROUP_TWO : JSON_GROUP_ONE,
                            "openai", "routed-model", 100, 50, 20L);
                });
    }

    private void wireFakeStore() {
        org.mockito.Mockito.doAnswer(inv -> {
            jobFace.set(new QaGenJobView(DOC_ID, inv.getArgument(1), QaGenJobView.STATUS_RUNNING,
                    inv.getArgument(4), 0, List.of(), 0, 0, inv.getArgument(3), null));
            return null;
        }).when(jobStore).startRun(anyLong(), anyString(), anyLong(), anyString(), anyInt());
        org.mockito.Mockito.doAnswer(inv -> {
            QaGenJobView face = jobFace.get();
            String landedJson = inv.getArgument(2);
            jobFace.set(new QaGenJobView(DOC_ID, inv.getArgument(1), QaGenJobView.STATUS_RUNNING,
                    face.totalGroups(), face.doneGroups() + 1,
                    new ObjectMapper().readValue(landedJson, List.class),
                    inv.getArgument(5), inv.getArgument(6), face.model(), null));
            return null;
        }).when(jobStore).landGroup(anyLong(), anyString(), anyString(), anyInt(), anyInt(),
                anyInt(), anyInt());
        org.mockito.Mockito.doAnswer(inv -> {
            QaGenJobView face = jobFace.get();
            jobFace.set(new QaGenJobView(face.documentId(), inv.getArgument(0),
                    inv.getArgument(1), face.totalGroups(), face.doneGroups(),
                    face.items(), face.promptTokens(), face.completionTokens(),
                    face.model(), inv.getArgument(2)));
            return null;
        }).when(jobStore).finishRun(anyString(), anyString(), any());
        when(jobStore.findLatestByDocument(DOC_ID)).thenAnswer(inv -> jobFace.get());
    }

    private QaGenJobView awaitTerminal() {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            QaGenJobView face = service.jobStatus(DOC_ID);
            if (face != null && !QaGenJobView.STATUS_RUNNING.equals(face.status())) {
                return face;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("job run never reached a terminal status");
    }

    /** Key 3: group calls ride the shared pool while the landing face is
     *  replayed in group order — cumulative done-count 1..2 and question
     *  order never shuffle. */
    @Test
    void keyThree_groupCallsOnPoolThreads_landingReplayStaysGroupOrder() throws Exception {
        stubRun("3");
        wireFakeStore();

        QaGenSubmitView receipt = service.submit(request());
        assertEquals("accepted", receipt.mode());
        QaGenJobView face = awaitTerminal();

        assertEquals(QaGenJobView.STATUS_DONE, face.status());
        assertEquals(2, groupCallThreads.size(), "exactly one grouped call per group");
        assertTrue(groupCallThreads.stream().allMatch(name -> name.startsWith(POOL_PREFIX)),
                () -> "group calls must ride the shared fan-out pool, saw " + groupCallThreads);
        ArgumentCaptor<Integer> doneCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(jobStore, times(2)).landGroup(eq(DOC_ID), eq(receipt.runToken()),
                jsonCaptor.capture(), doneCaptor.capture(), anyInt(), anyInt(), anyInt());
        assertEquals(List.of(1, 2), doneCaptor.getAllValues(),
                "done-groups face counts up in group order");
        List<Map<String, Object>> cumulative = new ObjectMapper()
                .readValue(jsonCaptor.getAllValues().get(1), List.class);
        assertEquals(List.of("订单创建接口用什么方法？", "退款查询接口用什么方法？"),
                cumulative.stream().map(row -> row.get("question")).toList());
        verify(processEvents, atLeastOnce()).record(eq(JOB_KIND), eq(String.valueOf(DOC_ID)),
                eq(receipt.runToken()), eq("step"), anyString(), any());
    }

    /** Key 1: the serial baseline spawns no fan-out pool — every group call
     *  stays inline on the single job launcher thread. */
    @Test
    void keyOne_serialBaseline_groupCallsStayOnJobLauncherThread() {
        stubRun("1");
        wireFakeStore();

        QaGenSubmitView receipt = service.submit(request());
        QaGenJobView face = awaitTerminal();

        assertEquals(QaGenJobView.STATUS_DONE, face.status());
        assertEquals(2, groupCallThreads.size());
        assertTrue(groupCallThreads.stream().allMatch(name -> name.startsWith("qa-gen-job-")),
                () -> "serial spectrum must stay on the launcher thread, saw "
                        + groupCallThreads);
        assertEquals(1, groupCallThreads.stream().distinct().count(),
                "one launcher thread serves the whole serial run");
    }

    /** three-group job fixture (15 chunks, ceiling 5) with
 * the fan-out key pinned to 2: the second group dies on a provider
 * timeout while the first group is still streaming. The shared run
 * signal must keep the never-started third group away from the
 * provider, and the run closes FAILED on the honest timeout
 * attribution with zero cumulative landings. */
    @Test
    void timeoutGroupTripsRunSignal_neverStartedSiblingBurnsNoProviderCall() {
        stubThreeGroupRun("2");
        wireFakeStore();
        java.util.concurrent.atomic.AtomicInteger thirdGroupCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenAnswer(invocation -> {
                    ChatCompletionRequest req = invocation.getArgument(0);
                    String prompt = req.messages().stream()
                            .map(m -> m.content())
                            .filter(java.util.Objects::nonNull)
                            .reduce("", (a, b) -> a + "\n" + b);
                    if (prompt.contains("事实片段6")) {
                        throw new ServiceException("模型服务调用超时，本组未完成",
                                BizErrorCode.AI_TIMEOUT);
                    }
                    if (prompt.contains("事实片段11")) {
                        thirdGroupCalls.incrementAndGet();
                        return new LlmChatResult(JSON_GROUP_TWO, "openai", "routed-model",
                                100, 50, 20L);
                    }
                    Thread.sleep(1_500);
                    return new LlmChatResult(JSON_GROUP_ONE, "openai", "routed-model",
                            100, 50, 20L);
                });

        QaGenSubmitView receipt = service.submit(request());
        assertEquals("accepted", receipt.mode());
        QaGenJobView face = awaitTerminal();

        assertEquals(0, thirdGroupCalls.get(),
                "a tripped run must never launch the unstarted third group");
        assertEquals(QaGenJobView.STATUS_FAILED, face.status());
        assertNotNull(face.errorMsg());
        assertTrue(face.errorMsg().contains("超时") && face.errorMsg().contains("已停止未开始的分组"),
                () -> "honest time-budget terminal wording expected, saw " + face.errorMsg());
        verify(jobStore, never()).landGroup(anyLong(), anyString(), anyString(), anyInt(),
                anyInt(), anyInt(), anyInt());
        verify(processEvents, atLeastOnce()).record(eq(JOB_KIND), eq(String.valueOf(DOC_ID)),
                eq(receipt.runToken()), eq("error"), anyString(), any());
    }

    /** Negative drift pin for the job plane: a NON-timeout group failure
     *  never trips the run signal — every submitted group reaches the
     *  provider and the original cause stays unwrapped on the job row. */
    @Test
    void nonTimeoutFailure_keepsSiblingsRunningAndOriginalVocabulary() {
        stubThreeGroupRun("2");
        wireFakeStore();
        java.util.concurrent.atomic.AtomicInteger providerCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenAnswer(invocation -> {
                    ChatCompletionRequest req = invocation.getArgument(0);
                    providerCalls.incrementAndGet();
                    String prompt = req.messages().stream()
                            .map(m -> m.content())
                            .filter(java.util.Objects::nonNull)
                            .reduce("", (a, b) -> a + "\n" + b);
                    if (prompt.contains("事实片段6")) {
                        throw new ServiceException("模型服务连接失败，无法完成造题",
                                BizErrorCode.AI_FORMAT_ERROR);
                    }
                    Thread.sleep(300);
                    return new LlmChatResult(prompt.contains("事实片段11")
                            ? JSON_GROUP_TWO : JSON_GROUP_ONE,
                            "openai", "routed-model", 100, 50, 20L);
                });

        service.submit(request());
        QaGenJobView face = awaitTerminal();

        assertEquals(QaGenJobView.STATUS_FAILED, face.status());
        assertTrue(face.errorMsg().contains("模型服务连接失败"),
                () -> "non-timeout cause stays unwrapped, saw " + face.errorMsg());
        assertEquals(3, providerCalls.get(),
                "an untripped signal must not cancel any submitted sibling");
    }

    /** Three-group job fixture: 15 chunks over the ceiling 5 seal exactly
     *  three groups; group 2 is recognised by chunk 6, group 3 by chunk 11. */
    private void stubThreeGroupRun(String rawKey) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            chunks.add(Map.of("seq", i, "chapter", "验收标准",
                    "content", "创建订单接口 POST /api/v1/orders 事实片段" + i));
        }
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(chunks);
        when(coreGateway.getSysConfig("judge.qa-gen.group-overlap-ratio")).thenReturn("0");
        when(coreGateway.getSysConfig("judge.qa-gen.group-max-chunks")).thenReturn("5");
        when(coreGateway.getSysConfig(GroupParallelFanout.KEY_GROUP_PARALLEL))
                .thenReturn(rawKey);
    }

    /** Fail-closed drift pin: the group walk collects first and folds after,
     *  so an aborting second group lands ZERO cumulative rows — the old
     *  serial face had already written group 1's partial items into the job
     *  row before failing. The human cause stays unwrapped on the row. */
    @Test
    void failClosed_abortGroupLandsZeroRowsAndClosesRunFailed() {
        stubRun("3");
        wireFakeStore();
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenAnswer(invocation -> {
                    ChatCompletionRequest req = invocation.getArgument(0);
                    boolean secondGroup = req.messages().stream()
                            .anyMatch(m -> m.content() != null
                                    && m.content().contains("事实片段6"));
                    if (secondGroup) {
                        throw new ServiceException("模型服务连接失败，无法完成造题",
                                BizErrorCode.AI_FORMAT_ERROR);
                    }
                    return new LlmChatResult(JSON_GROUP_ONE, "openai", "routed-model",
                            100, 50, 20L);
                });

        QaGenSubmitView receipt = service.submit(request());
        QaGenJobView face = awaitTerminal();

        assertEquals(QaGenJobView.STATUS_FAILED, face.status());
        assertNotNull(face.errorMsg());
        assertTrue(face.errorMsg().contains("模型服务连接失败"),
                () -> "abort vocabulary stays recognisable, saw " + face.errorMsg());
        verify(jobStore, never()).landGroup(anyLong(), anyString(), anyString(), anyInt(),
                anyInt(), anyInt(), anyInt());
        verify(processEvents, atLeastOnce()).record(eq(JOB_KIND), eq(String.valueOf(DOC_ID)),
                eq(receipt.runToken()), eq("error"), anyString(), any());
    }
}
