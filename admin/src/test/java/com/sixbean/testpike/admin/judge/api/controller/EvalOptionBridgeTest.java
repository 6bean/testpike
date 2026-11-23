package com.sixbean.testpike.admin.judge.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.judge.api.dto.EvalSetVersionOptionView;
import com.sixbean.testpike.admin.judge.api.dto.JudgeRubricOptionView;
import com.sixbean.testpike.admin.judge.infrastructure.remote.client.CoreJudgeClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Enumeration picker doors added by segment A (evalset-version list on
 * /api/v1/eval-sets/evalsets, rubric-family list on /api/v1/judge-runs/rubrics).
 * <p>Assertion faces: ①both doors echo core's rows field-for-field — the
 * latest-version and digest decisions are core's single source, the bridge
 * adds zero judgement (red line: no duplicate validation in the app layer);
 * ②permission doors ride the established judge key family (read-only picker
 * lanes: evalset list = perm:judge:evalset:view, rubric list =
 * perm:judge:run:view) so no new key is invented for a read door.
 * <p>CoreJudgeClient is the integration-boundary stand-in (bridge contract
 * L2 isolation, EvalSetBridgeTest/JudgeRunBridgeTest precedent); the real
 * admin→core→DB chain is covered by the L3 J-family script reuse at batch
 * close .
 */
class EvalOptionBridgeTest {

    private final CoreJudgeClient coreClient = mock(CoreJudgeClient.class);
    private final EvalSetController evalSetController = new EvalSetController(
            coreClient, mock(com.sixbean.testpike.admin.judge.infrastructure.remote.client.JudgeAiClient.class),
            mock(com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreDocumentClient.class),
            mock(com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard.class));
    private final JudgeRunController runController = new JudgeRunController(coreClient);

    @BeforeEach
    void login() {
        UserContext.set(new CurrentUser(7L, "u", "名", "部", List.of("TEST_LEAD"), 0L, null));
    }

    @AfterEach
    void logout() {
        UserContext.clear();
    }

    @Test
    void evalsetVersionDoorEchoesCoreRowsUntouched() {
        when(coreClient.listEvalsetVersions()).thenReturn(List.of(
                new EvalSetVersionOptionView("回归集", 3, "abc123", 42),
                new EvalSetVersionOptionView("回归集", 2, "def456", 37)));

        Response<List<EvalSetVersionOptionView>> resp = evalSetController.evalsets();

        assertEquals("0", resp.getCode());
        assertNotNull(resp.getData());
        assertEquals(2, resp.getData().size());
        assertEquals(3, resp.getData().get(0).version());
        assertEquals("abc123", resp.getData().get(0).snapshotDigest());
        assertEquals(42, resp.getData().get(0).sampleCount());
        assertEquals("def456", resp.getData().get(1).snapshotDigest());
    }

    @Test
    void rubricFamilyDoorEchoesCoreRowsUntouched() {
        when(coreClient.listRubricFamilies()).thenReturn(List.of(
                new JudgeRubricOptionView(11L, "用例质量", "用例生成", 2, 70, 1, "2026-11-12 10:00:00")));

        Response<List<JudgeRubricOptionView>> resp = runController.rubrics();

        assertEquals("0", resp.getCode());
        assertNotNull(resp.getData());
        JudgeRubricOptionView head = resp.getData().get(0);
        assertEquals(11L, head.id());
        assertEquals("用例质量", head.name());
        assertEquals("用例生成", head.domain());
        assertEquals(2, head.version());
        assertEquals(70, head.passScore());
        assertEquals(1, head.status());
        assertEquals("2026-11-12 10:00:00", head.createdAt());
    }

    @Test
    void pickerDoorsRideTheEstablishedJudgeReadKeys() throws Exception {
        Method evalsets = EvalSetController.class.getMethod("evalsets");
        Method rubrics = JudgeRunController.class.getMethod("rubrics");
        assertEquals("perm:judge:evalset:view",
                evalsets.getAnnotation(RequirePermission.class).value());
        assertEquals("perm:judge:run:view",
                rubrics.getAnnotation(RequirePermission.class).value());
    }
}
