package com.sixbean.testpike.aiservice.judge.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedger;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedgerStore;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenJobView;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenPreviewView;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenRequest;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenSubmitView;
import com.sixbean.testpike.aiservice.judge.domain.component.QaGenJobStore;
import com.sixbean.testpike.aiservice.judge.domain.component.QaGenQueryWindow;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEvent;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * piece 4 follow-up (question-door job plane, : the judge QA
 * door keeps the synchronous answer only while the sealed group count stays
 * inside {@code judge.qa-gen.sync-group-max} (default 1); over that cap the
 * submit face accepts a runToken-backed job instead of blocking the caller
 * until the provider window expires. Every landed group persists cumulative
 * items into the job row and appends one viewer-visible process event through
 * the shared {@link TaskProcessEventStore} (level face stays inside the
 * closed thinking/step/error set). A closed grouped-intake door honestly
 * disables the job face naming its own key; a failing group lands a failed
 * status plus an error event — never a silent half-run.
 */
class EvalQuestionGenJobPlaneTest {

    private static final long DOC_ID = 92L;
    private static final long PROJECT_ID = 32L;
    private static final long OPERATOR_ID = 16L;
    private static final String JOB_KIND = "qa-gen";

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

    /** In-run state mirror the fake store answers from, so the background
     *  thread's terminal status is observable without touching a database. */
    private final AtomicReference<QaGenJobView> jobFace = new AtomicReference<>();

    private static final String VALID_LLM_JSON = """
            {"items":[{"question":"订单创建接口用什么方法？","answer":"POST /api/v1/orders",
            "sourceAnchor":"创建订单接口 POST /api/v1/orders"}]}""";

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG);
    }

    private static QaGenRequest request() {
        return new QaGenRequest(PROJECT_ID, DOC_ID, null, OPERATOR_ID);
    }

    private static Map<String, Object> chunk(int seq) {
        return Map.of("seq", seq, "chapter", "验收标准",
                "content", "创建订单接口 POST /api/v1/orders 事实片段" + seq);
    }

    private static List<Map<String, Object>> chunks(int n) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(chunk(i));
        }
        return out;
    }

    /** Deterministic seal face: chunk ceiling 5 over 25 chunks = 5 groups;
     *  overlap tail removed so group planning never depends on timing. */
    private void stubSealedGroups(int groups) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(chunks(25));
        when(coreGateway.getSysConfig("judge.qa-gen.group-overlap-ratio"))
                .thenReturn("0");
        when(coreGateway.getSysConfig("judge.qa-gen.group-max-chunks"))
                .thenReturn(String.valueOf(25 / groups));
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenReturn(new LlmChatResult(VALID_LLM_JSON, "openai", "routed-model",
                        100, 50, 20L));
    }

    /** Fake store wired to a mirrored job face, answering findLatestByDocument
     *  from what startRun/landGroup/finishRun just persisted. */
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

    /** Polls the mirrored face until the run leaves the running state. */
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

    /** Over-cap submit takes the job path: accepted receipt, then every group
     *  lands cumulative items on the job row and books one step event; the
     *  terminal row keeps every merged item and the finish books a final step. */
    @Test
    void submitOverSyncCap_acceptsRunThenLandsEveryGroupAndBooksEvents() {
        stubSealedGroups(5);
        wireFakeStore();

        QaGenSubmitView receipt = service.submit(request());

        assertEquals("accepted", receipt.mode(), "5 groups over the cap must take the job face");
        assertNotNull(receipt.runToken(), "accepted receipt carries the run token");
        assertNull(receipt.preview(), "job face never answers an inline preview");

        QaGenJobView face = awaitTerminal();
        assertEquals(QaGenJobView.STATUS_DONE, face.status());
        assertEquals(5, face.doneGroups());
        assertFalse(face.items().isEmpty(), "landed items must survive into the job face");

        verify(jobStore).startRun(eq(DOC_ID), eq(receipt.runToken()), eq(PROJECT_ID),
                eq("routed-model"), eq(5));
        ArgumentCaptor<Integer> doneCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(jobStore, times(5)).landGroup(eq(DOC_ID), eq(receipt.runToken()),
                anyString(), doneCaptor.capture(), anyInt(), anyInt(), anyInt());
        assertEquals(List.of(1, 2, 3, 4, 5), doneCaptor.getAllValues(),
                "done-groups face counts up exactly once per landed group");
        verify(jobStore).finishRun(eq(receipt.runToken()), eq(QaGenJobView.STATUS_DONE), eq(null));

        ArgumentCaptor<String> levelCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
        verify(processEvents, atLeastOnce()).record(eq(JOB_KIND), eq(String.valueOf(DOC_ID)),
                eq(receipt.runToken()), levelCaptor.capture(), summaryCaptor.capture(), any());
        assertEquals(5, levelCaptor.getAllValues().stream().filter("step"::equals).count(),
                "one landed group books exactly one step event");
        assertTrue(summaryCaptor.getAllValues().stream().allMatch(s -> !s.isBlank()),
                "every process event keeps a non-blank readable summary");
    }

    /** A group call that dies lands a failed status with the human cause and
     *  books an error event — never a silent half-run. */
    @Test
    void submitGroupFailure_landsFailedStatusAndBooksErrorEvent() {
        stubSealedGroups(5);
        wireFakeStore();
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenReturn(new LlmChatResult(VALID_LLM_JSON, "openai", "routed-model",
                        100, 50, 20L))
                .thenThrow(new IllegalStateException("provider exploded"));

        QaGenSubmitView receipt = service.submit(request());
        QaGenJobView face = awaitTerminal();

        assertEquals(QaGenJobView.STATUS_FAILED, face.status());
        assertNotNull(face.errorMsg(), "failed run keeps the readable cause on the row");
        verify(processEvents, atLeastOnce()).record(eq(JOB_KIND), eq(String.valueOf(DOC_ID)),
                eq(receipt.runToken()), eq("error"), anyString(), any());
        verify(jobStore).finishRun(eq(receipt.runToken()), eq(QaGenJobView.STATUS_FAILED),
                anyString());
    }

    /** gap fix (job plane fidelity): the over-cap background run is
 * the same generation as the synchronous door — chunks sealed into
 * groups, items merged, tokens summed — so it must book exactly one
 * qa-gen fidelity row at terminal success, with the group count as its
 * node face and the summed per-call prompt as its injected face, never
 * a silently untracked run. */
    @Test
    void submitOverSyncCap_terminalSuccessBooksOneQaGenFidelityRow() {
        stubSealedGroups(5);
        wireFakeStore();

        service.submit(request());
        QaGenJobView face = awaitTerminal();
        assertEquals(QaGenJobView.STATUS_DONE, face.status());

        ArgumentCaptor<ExtractFidelityLedger.Entry> row =
                ArgumentCaptor.forClass(ExtractFidelityLedger.Entry.class);
        verify(fidelityStore, times(1)).upsertIfChanged(row.capture());
        ExtractFidelityLedger.Entry entry = row.getValue();
        assertEquals(ExtractFidelityLedger.TASK_QA_GEN, entry.taskType());
        assertEquals(DOC_ID, entry.refId());
        assertEquals(25, entry.chunkCount(), "chunk face = the 25 source chunks");
        assertEquals(5, entry.nodeCount(), "node face = the 5 sealed groups");
        assertEquals(500, entry.injectedTokens(),
                "injected face = 5 group calls x 100 prompt tokens summed at the seam");
        assertEquals(1, entry.itemCount(), "same question across groups merges to one item");
        assertEquals(1, entry.anchoredItemCount());
    }

    /** A run that dies mid-way is NOT a generation: like the synchronous
     *  door (which throws before booking), the failed background body books
     *  zero fidelity rows — a half-run must not pollute the retention account. */
    @Test
    void submitGroupFailure_failedRunBooksNoFidelityRow() {
        stubSealedGroups(5);
        wireFakeStore();
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenThrow(new IllegalStateException("provider exploded"));

        service.submit(request());
        assertEquals(QaGenJobView.STATUS_FAILED, awaitTerminal().status());
        verify(fidelityStore, never()).upsertIfChanged(any());
    }

    /** Within-cap submit stays fully synchronous: inline preview, zero job
     *  rows, zero events — the legacy answer face untouched. */
    @Test
    void submitWithinSyncCap_answersPreviewInlineAndOpensNoJob() {
        when(coreGateway.getSysConfig("judge.qa-gen.sync-group-max")).thenReturn("2");
        stubSealedGroups(1);

        QaGenSubmitView receipt = service.submit(request());

        assertEquals("sync", receipt.mode());
        assertNull(receipt.runToken());
        QaGenPreviewView preview = receipt.preview();
        assertNotNull(preview);
        assertEquals(1, preview.items().size());
        verify(jobStore, never()).startRun(anyLong(), anyString(), anyLong(), anyString(), anyInt());
        verify(processEvents, never()).record(anyString(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    /** The closed grouped-intake door honestly disables the job face naming
     *  its own key, burning zero LLM calls and opening no row. */
    @Test
    void submitDoorOff_honestDisabledRejectionNamingItsKey() {
        when(coreGateway.getSysConfig(EvalQuestionGenServiceImpl.KEY_GROUPED_INTAKE_ENABLED))
                .thenReturn("false");
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(chunks(3));

        ServiceException e = assertThrows(ServiceException.class, () -> service.submit(request()));
        assertTrue(e.getMessage().contains("ai.judge.grouped-intake.enabled"),
                "closed-door rejection must name the switch the user can open");
        verify(llmChain, never()).chat(any(), anyLong(), any(), anyBoolean());
        verify(jobStore, never()).startRun(anyLong(), anyString(), anyLong(), anyString(), anyInt());
    }

    /** Status and event windows are thin delegates: latest row per document,
     *  cursor replay straight through the shared event store. */
    @Test
    void statusAndEvents_delegateToSharedDoors() {
        QaGenJobView row = new QaGenJobView(DOC_ID, "tok-1", QaGenJobView.STATUS_DONE,
                3, 3, List.of(Map.of("question", "q")), 300, 150, "m", null);
        when(jobStore.findLatestByDocument(DOC_ID)).thenReturn(row);
        assertEquals(row, service.jobStatus(DOC_ID));

        List<TaskProcessEvent> window = List.of(
                new TaskProcessEvent(7L, "step", "第1/3组完成", "{}", null));
        when(processEvents.fetchSince(JOB_KIND, String.valueOf(DOC_ID), "tok-1", 7L, 50))
                .thenReturn(window);
        assertEquals(window, service.jobEvents(DOC_ID, "tok-1", 7L, 50));
    }

    /** Every job-row write rides the run token in its predicate, so an old
     *  background run can never overwrite the newest row (runToken boundary). */
    @Test
    void jobStoreWrites_areBoundedByRunToken() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        QaGenJobStore store = new QaGenJobStore(jdbc, new ObjectMapper(),
                new QaGenQueryWindow(1800));

        store.landGroup(DOC_ID, "tok-9", "[]", 2, 5, 200, 90);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(),
                any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class));
        assertTrue(sql.getValue().contains("run_token = :runToken"),
                "landed-group update must be bounded by the run token");

        store.finishRun("tok-9", QaGenJobView.STATUS_DONE, null);
        verify(jdbc, atLeastOnce()).update(sql.capture(),
                any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class));
        assertTrue(sql.getValue().contains("run_token = :runToken"),
                "finish write must be bounded by the run token");
    }
}
