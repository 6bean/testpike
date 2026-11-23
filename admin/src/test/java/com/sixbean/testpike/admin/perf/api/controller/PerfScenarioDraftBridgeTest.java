package com.sixbean.testpike.admin.perf.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.perf.api.dto.PerfScenarioDraftRequest;
import com.sixbean.testpike.admin.perf.infrastructure.remote.client.CorePerfClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * admin bridge contract for the AI scenario-draft door (B-11 : a
 * draft feeds the wizard that saves project scenario assets, so the write-
 * asset management guard applies (same gate as scenario create — a view-only
 * member must not burn model tokens through the drafting door). The bridge
 * adds no validation of its own: switch verdicts and step trimming are core
 * semantics and the human wording rides through untranslated (red line 6).
 * <p>CorePerfClient/Guard stand-ins = L2 bridge isolation
 * (PerfWorkbenchBridgeTest precedent); the live admin→core→ai-service leg is
 * verified at L3/L4 with the wizard segment.
 */
class PerfScenarioDraftBridgeTest {

    private final CorePerfClient client = mock(CorePerfClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final PerfGateController controller = new PerfGateController(client, guard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private void login() {
        UserContext.set(new CurrentUser(7L, "u", "名", "部", List.of("TESTER"), 0L, null));
    }

    @Test
    void draftPassesManagementGuardAndForwardsToCore() {
        login();
        when(client.draftScenario(11L, "对下单接口施压 100 RPS")).thenReturn(
                Map.of("steps", List.of(Map.of("action", "http.request")),
                        "droppedCount", 0, "droppedReasons", List.of()));

        Map<String, Object> draft = controller.draftScenario(
                new PerfScenarioDraftRequest(11L, "对下单接口施压 100 RPS")).getData();

        assertEquals(1, ((List<?>) draft.get("steps")).size());
        verify(guard).assertCanManage(any(CurrentUser.class), eq(11L));
        verify(client).draftScenario(11L, "对下单接口施压 100 RPS");
    }

    @Test
    void closedSwitchWordingRidesThroughUntranslated() {
        login();
        when(client.draftScenario(anyLong(), anyString())).thenThrow(
                new ServiceException("压测 AI 应用点「scenario-draft」当前未开启：需要系统管理员在系统配置中将开关 ai.perf.scenario-draft 设为 true 后重试",
                        BizErrorCode.FORBIDDEN));

        ServiceException e = assertThrows(ServiceException.class, () -> controller.draftScenario(
                new PerfScenarioDraftRequest(11L, "打一下下单")));
        assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
        assertTrue(e.getMessage().contains("ai.perf.scenario-draft"),
                "core's key-naming refusal must reach the browser intact");
    }

    @Test
    void anonymousCallerFailsFastBeforeTheBridge() {
        // no login context: the door must reject on UserContext (401), not call core
        ServiceException e = assertThrows(ServiceException.class, () -> controller.draftScenario(
                new PerfScenarioDraftRequest(11L, "打一下下单")));
        assertEquals(BizErrorCode.UNAUTHORIZED, e.getCode());
        verify(client, org.mockito.Mockito.never()).draftScenario(anyLong(), anyString());
    }
}
