package com.sixbean.testpike.admin.judge.api.controller;

import com.sixbean.testpike.admin.ai.api.dto.DocumentInfoView;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreDocumentClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.judge.api.dto.EvalPublishSample;
import com.sixbean.testpike.admin.judge.api.dto.EvalSampleView;
import com.sixbean.testpike.admin.judge.api.dto.EvalSetAdoptedRaiseBody;
import com.sixbean.testpike.admin.judge.api.dto.EvalSetBatchRaiseBody;
import com.sixbean.testpike.admin.judge.api.dto.EvalSetPublishRequest;
import com.sixbean.testpike.admin.judge.api.dto.EvalSetRaiseBody;
import com.sixbean.testpike.admin.judge.api.dto.EvalSetVersionView;
import com.sixbean.testpike.admin.judge.api.dto.QaGenEventView;
import com.sixbean.testpike.admin.judge.api.dto.QaGenJobView;
import com.sixbean.testpike.admin.judge.api.dto.QaGenRunView;
import com.sixbean.testpike.admin.judge.api.dto.QaGenPreviewView;
import com.sixbean.testpike.admin.judge.api.dto.QaGenSubmitView;
import com.sixbean.testpike.admin.judge.infrastructure.remote.client.CoreJudgeClient;
import com.sixbean.testpike.admin.judge.infrastructure.remote.client.JudgeAiClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ③permission doors ride the dedicated judge keys : qa lanes =
 * perm:judge:qagen:run, publish = perm:judge:evalset:manage.
 */
class EvalSetBridgeTest {

    private final JudgeAiClient aiClient = mock(JudgeAiClient.class);
    private final CoreJudgeClient coreClient = mock(CoreJudgeClient.class);
    private final CoreDocumentClient documentClient = mock(CoreDocumentClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final EvalSetController controller =
            new EvalSetController(coreClient, aiClient, documentClient, guard);

    @BeforeEach
    void login() {
        UserContext.set(new CurrentUser(7L, "u", "名", "部", List.of("TEST_LEAD"), 0L, null));
    }

    @AfterEach
    void logout() {
        UserContext.clear();
    }

    /** Document row stub for the ownership guard the read doors ride on. */
    private void givenDocument(long documentId, long projectId) {
        when(documentClient.get(documentId)).thenReturn(new DocumentInfoView(
                documentId, projectId, "doc", "markdown", null, 0L, null, 1, 0, null, null,
                true, true, true, 0, null, null, null));
    }

    /** The submit door answers within the sync cap with the full inline preview. */
    @Test
    void submitDoorPassesParamsThroughAndEchoesSyncPreviewUntouched() {
        when(aiClient.submit(eq(11L), eq(501L), eq("glm-4-plus"), eq(7L)))
                .thenReturn(QaGenSubmitView.sync(new QaGenPreviewView(
                        List.of(Map.of("question", "q1", "answer", "a1", "sourceAnchor", "s1")),
                        3, 1200, 480, "glm-4-plus")));

        Response<QaGenSubmitView> resp = controller.previewQuestions(11L, 501L, "glm-4-plus");

        assertEquals("0", resp.getCode());
        assertNotNull(resp.getData());
        assertEquals(QaGenSubmitView.MODE_SYNC, resp.getData().mode());
        assertNull(resp.getData().runToken());
        QaGenPreviewView preview = resp.getData().preview();
        assertEquals(3, preview.chunkCount());
        assertEquals(1200, preview.promptTokens());
        assertEquals(480, preview.completionTokens());
        assertEquals("glm-4-plus", preview.model());
        assertEquals("q1", preview.items().get(0).get("question"));
        verify(aiClient).submit(eq(11L), eq(501L), eq("glm-4-plus"), eq(7L));
    }

    /** Beyond the sync cap the same door answers an accepted receipt, never a preview. */
    @Test
    void submitDoorEchoesAcceptedReceiptWithRunTokenAndNoPreview() {
        when(aiClient.submit(eq(11L), eq(501L), eq(null), eq(7L)))
                .thenReturn(QaGenSubmitView.accepted("run-abc"));

        Response<QaGenSubmitView> resp = controller.previewQuestions(11L, 501L, null);

        assertEquals(QaGenSubmitView.MODE_ACCEPTED, resp.getData().mode());
        assertEquals("run-abc", resp.getData().runToken());
        assertNull(resp.getData().preview());
    }

    /** The job face passes document + session operator through and returns all ten fields. */
    @Test
    void jobStatusDoorPassesDocumentAndOperatorAndReturnsLedgerFace() {
        givenDocument(501L, 11L);
        when(aiClient.jobStatus(eq(501L), eq(7L))).thenReturn(new QaGenJobView(
                501L, "run-abc", "running", 17, 3,
                List.of(Map.of("question", "q9", "answer", "a9", "sourceAnchor", "s9")),
                9000, 2200, "glm-4-plus", null));

        Response<QaGenJobView> resp = controller.qaGenJob(501L);

        assertEquals("0", resp.getCode());
        assertEquals("running", resp.getData().status());
        assertEquals(17, resp.getData().totalGroups());
        assertEquals(3, resp.getData().doneGroups());
        assertEquals("run-abc", resp.getData().runToken());
        assertEquals(9000, resp.getData().promptTokens());
        assertEquals("q9", resp.getData().items().get(0).get("question"));
        // Ownership segment of the read door: guard sees the document's own project.
        verify(guard).assertCanView(any(CurrentUser.class), eq(11L));
    }

    /** The event tail replays the cursor window verbatim — no merge, no truncation. */
    @Test
    void jobEventsDoorForwardsCursorWindowAndKeepsFrameShape() {
        givenDocument(501L, 11L);
        when(aiClient.jobEvents(eq(501L), eq("run-abc"), eq(42L), eq(50), eq(7L)))
                .thenReturn(List.of(
                        new QaGenEventView(43, "step", "group 4 landed", "{\"tokens\":512}",
                                "2026-11-10T18:00:00"),
                        new QaGenEventView(44, "thinking", "reasoning", null, "2026-11-10T18:00:05")));

        Response<List<QaGenEventView>> resp = controller.qaGenEvents(501L, "run-abc", 42L, 50);

        assertEquals(2, resp.getData().size());
        assertEquals(43, resp.getData().get(0).cursor());
        assertEquals("group 4 landed", resp.getData().get(0).summary());
        assertEquals("{\"tokens\":512}", resp.getData().get(0).detail());
        assertEquals(44, resp.getData().get(1).cursor());
        verify(guard).assertCanView(any(CurrentUser.class), eq(11L));
    }

    /** limit=null defers to the ai-service hot key instead of inventing an admin default. */
    @Test
    void jobEventsDoorForwardsNullLimitAsIs() {
        givenDocument(501L, 11L);
        when(aiClient.jobEvents(eq(501L), eq("run-abc"), anyLong(), eq(null), eq(7L)))
                .thenReturn(List.of());

        Response<List<QaGenEventView>> resp = controller.qaGenEvents(501L, "run-abc", 0L, null);

        assertEquals(0, resp.getData().size());
        verify(aiClient).jobEvents(eq(501L), eq("run-abc"), eq(0L), eq(null), eq(7L));
    }

    /** Revisit door : the historical run list rides the
 * document's own ownership segment and echoes ai-service rows untouched. */
    @Test
    void qaGenRunsDoorForwardsDocumentLimitAndEchoesRunRows() {
        givenDocument(501L, 11L);
        when(aiClient.qaGenRuns(eq(501L), eq(20), eq(7L)))
                .thenReturn(List.of(
                        new QaGenRunView("run-abc", "done", 44,
                                "2026-11-10T18:00:00", "2026-11-10T18:06:12"),
                        new QaGenRunView("run-def", "running", 7,
                                "2026-11-10T19:00:00", "2026-11-10T19:00:31")));

        Response<List<QaGenRunView>> resp = controller.qaGenRuns(501L, 20);

        assertEquals("0", resp.getCode());
        assertEquals(2, resp.getData().size());
        assertEquals("run-abc", resp.getData().get(0).runToken());
        assertEquals("done", resp.getData().get(0).status());
        assertEquals(44, resp.getData().get(0).eventCount());
        assertEquals("2026-11-10T18:06:12", resp.getData().get(0).lastAt());
        verify(guard).assertCanView(any(CurrentUser.class), eq(11L));
    }

    /** limit=null defers to the ai-service hot key — no second admin default. */
    @Test
    void qaGenRunsDoorForwardsNullLimitAsIs() {
        givenDocument(501L, 11L);
        when(aiClient.qaGenRuns(eq(501L), eq(null), eq(7L))).thenReturn(List.of());

        Response<List<QaGenRunView>> resp = controller.qaGenRuns(501L, null);

        assertEquals(0, resp.getData().size());
        verify(aiClient).qaGenRuns(eq(501L), eq(null), eq(7L));
    }

    
    @Test
    void publishFillsOperatorFromSessionAndPassesSamplesThrough() {
        when(coreClient.publish(any())).thenReturn(
                new EvalSetVersionView("结算回归集", 4, "ab12cd34", 12));

        Response<EvalSetVersionView> resp = controller.publish(new EvalSetPublishRequest(
                "结算回归集", List.of(new EvalPublishSample(
                        "问题原文", "参考答案", List.of("锚点原句"), "ai-generated", 0))));

        assertEquals("0", resp.getCode());
        assertEquals(4, resp.getData().version());
        assertEquals("ab12cd34", resp.getData().snapshotDigest());
        assertEquals(12, resp.getData().sampleCount());
        var captor = org.mockito.ArgumentCaptor.forClass(
                com.sixbean.testpike.admin.judge.api.dto.EvalSetPublishBody.class);
        verify(coreClient).publish(captor.capture());
        assertEquals("结算回归集", captor.getValue().setName());
        assertEquals(7L, captor.getValue().createdBy());
        assertEquals("问题原文", captor.getValue().samples().get(0).input());
        assertEquals("参考答案", captor.getValue().samples().get(0).expected());
        assertEquals(List.of("锚点原句"), captor.getValue().samples().get(0).referenceDocs());
        assertEquals("ai-generated", captor.getValue().samples().get(0).source());
        assertEquals(0, captor.getValue().samples().get(0).sensitive());
    }

    /** Permission gate: qa lanes carry perm:judge:qagen:run, publish carries evalset:manage. */
    @Test
    void judgeLanesCarryTheirRuledKeys() throws Exception {
        Method preview = EvalSetController.class.getMethod("previewQuestions",
                Long.class, Long.class, String.class);
        Method publish = EvalSetController.class.getMethod("publish",
                EvalSetPublishRequest.class);
        Method job = EvalSetController.class.getMethod("qaGenJob", Long.class);
        Method events = EvalSetController.class.getMethod("qaGenEvents",
                Long.class, String.class, long.class, Integer.class);
        Method runs = EvalSetController.class.getMethod("qaGenRuns",
                Long.class, Integer.class);
        assertEquals("perm:judge:qagen:run",
                preview.getAnnotation(RequirePermission.class).value());
        assertEquals("perm:judge:evalset:manage",
                publish.getAnnotation(RequirePermission.class).value());
        assertEquals("perm:judge:qagen:run",
                job.getAnnotation(RequirePermission.class).value());
        assertEquals("perm:judge:qagen:run",
                events.getAnnotation(RequirePermission.class).value());
        assertEquals("perm:judge:qagen:run",
                runs.getAnnotation(RequirePermission.class).value());
    }

    /** AW1 read door: samples GET forwards setName/version untouched, echoes core rows. */
    @Test
    void samplesDoorForwardsSetNameVersionAndEchoesRows() {
        List<EvalSampleView> rows = List.of(new EvalSampleView(
                1L, "golden.set", 2, "digest-2", "需求甲", "期望甲", List.of("doc-1"),
                "manual", 0, 9701L, "2026-11-12 10:00:00"));
        when(coreClient.samples("golden.set", 2)).thenReturn(rows);

        var result = controller.samples("golden.set", 2);

        assertEquals(rows, result.getData());
        verify(coreClient).samples("golden.set", 2);
    }

    /**
     * AW2 raise doors: createdBy is replaced by the session user (client claim
     * discarded), id lists forwarded as given, core version view echoed —
     * version bump / pairing / terminal-state / eligibility gates stay
     * single-source in core (bridge carries zero validation of its own).
     */
    @Test
    void raiseDoorsStampSessionIdentityAndForwardIdLists() {
        EvalSetVersionView raised = new EvalSetVersionView("golden.set", 3, "digest-3", 5);
        when(coreClient.raise(any(), any(), any())).thenReturn(raised);
        when(coreClient.raiseFromBatch(any(), any(), any())).thenReturn(raised);
        when(coreClient.raiseFromAdopted(any(), any(), any())).thenReturn(raised);

        controller.raise(new EvalSetRaiseBody("golden.set", List.of(11L, 12L), 999L));
        controller.raiseFromBatch(
                new EvalSetBatchRaiseBody("golden.set", List.of(21L), 999L));
        controller.raiseFromAdopted(
                new EvalSetAdoptedRaiseBody("golden.set", List.of(31L, 32L), 999L));

        verify(coreClient).raise("golden.set", List.of(11L, 12L), 7L);
        verify(coreClient).raiseFromBatch("golden.set", List.of(21L), 7L);
        verify(coreClient).raiseFromAdopted("golden.set", List.of(31L, 32L), 7L);
    }

    /** Permission gate: samples rides evalset:view, the three raise doors ride evalset:manage. */
    @Test
    void facadeDoorsCarryTheirRuledKeys() throws Exception {
        Method samples = EvalSetController.class.getMethod("samples", String.class, int.class);
        Method raise = EvalSetController.class.getMethod("raise", EvalSetRaiseBody.class);
        Method fromBatch = EvalSetController.class.getMethod(
                "raiseFromBatch", EvalSetBatchRaiseBody.class);
        Method fromAdopted = EvalSetController.class.getMethod(
                "raiseFromAdopted", EvalSetAdoptedRaiseBody.class);
        assertEquals("perm:judge:evalset:view",
                samples.getAnnotation(RequirePermission.class).value());
        assertEquals("perm:judge:evalset:manage",
                raise.getAnnotation(RequirePermission.class).value());
        assertEquals("perm:judge:evalset:manage",
                fromBatch.getAnnotation(RequirePermission.class).value());
        assertEquals("perm:judge:evalset:manage",
                fromAdopted.getAnnotation(RequirePermission.class).value());
    }
}
