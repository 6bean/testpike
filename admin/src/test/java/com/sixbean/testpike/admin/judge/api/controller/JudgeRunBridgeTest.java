package com.sixbean.testpike.admin.judge.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.judge.api.dto.JudgeDispatchReceiptView;
import com.sixbean.testpike.admin.judge.api.dto.JudgeRunCreateBody;
import com.sixbean.testpike.admin.judge.api.dto.JudgeRunDispatchBody;
import com.sixbean.testpike.admin.judge.api.dto.JudgeRunPageView;
import com.sixbean.testpike.admin.judge.api.dto.JudgeRunView;
import com.sixbean.testpike.admin.judge.infrastructure.remote.client.CoreJudgeClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Evaluation-run facade bridge contract (AW4 admin doors over the core judge
 * run plane — L2 isolation of the integration boundary, JudgeReportBridgeTest
 * precedent; the real admin→core→DB chain is covered by the L3 J-family
 * script reuse at batch close).
 * <p>Assertion faces: ①list door forwards filters/page params untouched and
 * echoes the core page view without any second judgement; ②create door stamps
 * createdBy from the login session — a client-self-reported id is discarded
 * (identity trust boundary); anchor rejection codes come from core unchanged
 * (bridge has zero validation of its own, red line: judgement single-source);
 * ③dispatch door forwards runId + optional provider + operator header;
 * ④permission doors ride the dedicated judge key family :
 * read = perm:judge:run:view, create/dispatch = perm:judge:run:create.
 */
class JudgeRunBridgeTest {

    private final CoreJudgeClient coreClient = mock(CoreJudgeClient.class);
    private final JudgeRunController controller = new JudgeRunController(coreClient);

    @BeforeEach
    void login() {
        UserContext.set(new CurrentUser(7L, "u", "名", "部", List.of("TEST_LEAD"), 0L, null));
    }

    @AfterEach
    void logout() {
        UserContext.clear();
    }

    @Test
    void listDoorForwardsFiltersAndEchoesPageUntouched() {
        JudgeRunPageView page = new JudgeRunPageView(2, 10, 25, List.of(new JudgeRunView(
                101L, "回归标准", "用例生成", 3, Map.of("passScore", 60),
                "golden.set", 2, "digest-2", "external", "target-x", null, 2, 7L,
                "2026-11-12 10:00:00", "2026-11-12 10:05:00")));
        when(coreClient.listRuns("golden.set", 2, 2, 10)).thenReturn(page);

        var result = controller.list("golden.set", 2, 2, 10);

        assertEquals(page, result.getData());
        verify(coreClient).listRuns(eq("golden.set"), eq(2), eq(2), eq(10));
    }

    @Test
    void createDoorStampsSessionIdentityAndDiscardsClientClaim() {
        when(coreClient.createRun(any())).thenReturn(new JudgeRunView(
                102L, "回归标准", "用例生成", 3, Map.of(), "golden.set", 2,
                "digest-2", "pipeline", "cfg-9", 501L, 0, 7L, "2026-11-12 11:00:00", null));

        // The payload claims operator 999 — the door must replace it with the session user.
        controller.create(new JudgeRunCreateBody(9L, "golden.set", 2, "digest-2",
                "pipeline", "cfg-9", 501L, 999L));

        ArgumentCaptor<JudgeRunCreateBody> captor =
                ArgumentCaptor.forClass(JudgeRunCreateBody.class);
        verify(coreClient).createRun(captor.capture());
        JudgeRunCreateBody sent = captor.getValue();
        assertEquals(7L, sent.createdBy());
        assertEquals(9L, sent.rubricId());
        assertEquals("golden.set", sent.evalsetName());
        assertEquals(2, sent.evalsetVersion());
        assertEquals("digest-2", sent.evalsetDigest());
        assertEquals("pipeline", sent.targetType());
        assertEquals("cfg-9", sent.targetRef());
        assertEquals(501L, sent.llmConfigId());
    }

    @Test
    void dispatchDoorForwardsRunProviderAndOperatorHeader() {
        when(coreClient.dispatch(eq(102L), eq("zhipu"), eq(7L)))
                .thenReturn(new JudgeDispatchReceiptView(20, "glm-4-plus"));

        var result = controller.dispatch(102L, new JudgeRunDispatchBody("zhipu"));

        assertEquals(new JudgeDispatchReceiptView(20, "glm-4-plus"), result.getData());
        verify(coreClient).dispatch(102L, "zhipu", 7L);
    }

    @Test
    void dispatchDoorToleratesMissingBodyWithNullProvider() {
        when(coreClient.dispatch(eq(103L), isNull(), eq(7L)))
                .thenReturn(new JudgeDispatchReceiptView(4, "glm-4-plus"));

        controller.dispatch(103L, null);

        verify(coreClient).dispatch(103L, null, 7L);
    }

    /** Permission gate: run lanes carry the dedicated keys . */
    @Test
    void runDoorsCarryDedicatedJudgeKeys() throws NoSuchMethodException {
        Method list = JudgeRunController.class.getMethod("list", String.class,
                Integer.class, int.class, int.class);
        Method create = JudgeRunController.class.getMethod("create", JudgeRunCreateBody.class);
        Method dispatch = JudgeRunController.class.getMethod("dispatch", long.class,
                JudgeRunDispatchBody.class);
        assertEquals("perm:judge:run:view", list.getAnnotation(RequirePermission.class).value());
        assertEquals("perm:judge:run:create", create.getAnnotation(RequirePermission.class).value());
        assertEquals("perm:judge:run:create",
                dispatch.getAnnotation(RequirePermission.class).value());
    }
}
