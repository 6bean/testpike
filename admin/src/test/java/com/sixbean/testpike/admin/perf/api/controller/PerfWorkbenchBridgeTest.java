package com.sixbean.testpike.admin.perf.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.perf.api.dto.PerfScenarioSaveRequest;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class PerfWorkbenchBridgeTest {

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

    private PerfScenarioSaveRequest scenarioRequest() {
        return new PerfScenarioSaveRequest(11L, "下单峰值验收", 20L, "CONSTANT_RPS",
                Map.of("targetRps", 100, "durationSeconds", 300), "PER_VUSER", 500,
                List.of(Map.of("stepNo", 1, "method", "GET", "url", "https://api.example.com/orders")),
                Map.of("p95Ms", 800.0));
    }

    @Test
    void 场景列表_管理闸_透传core() {
        login();
        when(client.listScenarios(11L)).thenReturn(List.of(Map.of("id", 3L, "name", "下单峰值验收")));
        assertEquals(1, controller.listScenarios(11L).getData().size());
        verify(guard).assertCanManage(any(CurrentUser.class), eq(11L));
        verify(client).listScenarios(11L);
    }

    @Test
    void 场景详情_管理闸_id透传() {
        login();
        when(client.viewScenario(11L, 3L)).thenReturn(Map.of("id", 3L, "pressureModel", "RAMP"));
        assertEquals("RAMP", controller.viewScenario(11L, 3L).getData().get("pressureModel"));
        verify(client).viewScenario(11L, 3L);
    }

    @Test
    void 场景新增_管理闸先行_返回新id() {
        login();
        when(client.createScenario(any())).thenReturn(88L);
        PerfScenarioSaveRequest request = scenarioRequest();
        assertEquals(88L, controller.createScenario(request).getData());
        verify(guard).assertCanManage(any(CurrentUser.class), eq(11L));
        verify(client).createScenario(request);
    }

    @Test
    void 场景编辑_入参项目定闸_位置id与主体下发() {
        login();
        controller.updateScenario(11L, 3L, scenarioRequest());
        verify(guard).assertCanManage(any(CurrentUser.class), eq(11L));
        verify(client).updateScenario(eq(11L), eq(3L), any(PerfScenarioSaveRequest.class));
    }

    @Test
    void 非管理者_场景写操作拒且core零下发() {
        login();
        doThrow(new ServiceException("该操作仅项目负责人可执行", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanManage(any(CurrentUser.class), anyLong());
        assertThrows(ServiceException.class, () -> controller.createScenario(scenarioRequest()));
        verify(client, never()).createScenario(any());
    }

    @Test
    void 发起压测_管理闸_运行id透传() {
        login();
        when(client.launchRun(11L, 3L, null)).thenReturn(77L);
        assertEquals(77L, controller.launchRun(11L, 3L, null).getData());
        verify(guard).assertCanManage(any(CurrentUser.class), eq(11L));
        verify(client).launchRun(11L, 3L, null);
    }

    /** GB-1: the launch-time environment override is passed through the bridge as-is
     *  (absent = null = scenario default; the admin layer owns no fallback logic). */
    @Test
    void 发起压测_环境覆盖透传_缺省为null() {
        login();
        when(client.launchRun(11L, 3L, 9L)).thenReturn(78L);
        assertEquals(78L, controller.launchRun(11L, 3L, 9L).getData());
        verify(client).launchRun(11L, 3L, 9L);
    }

    @Test
    void 未登录_发起压测拒且core零下发() {
        assertThrows(ServiceException.class, () -> controller.launchRun(11L, 3L, null));
        verify(client, never()).launchRun(anyLong(), anyLong(), any());
    }

    @Test
    void 运行列表_成员可读_按场景寻址() {
        login();
        when(client.listRuns(11L, 3L)).thenReturn(List.of(Map.of("id", 77L, "status", 1)));
        assertEquals(1, controller.listRuns(11L, 3L).getData().size());
        verify(guard).assertCanView(any(CurrentUser.class), eq(11L));
        verify(client).listRuns(11L, 3L);
    }

    @Test
    void 运行详情_成员可读_runId透传() {
        login();
        when(client.viewRun(11L, 77L)).thenReturn(Map.of("id", 77L, "engine", "K6"));
        assertEquals("K6", controller.viewRun(11L, 77L).getData().get("engine"));
        verify(guard).assertCanView(any(CurrentUser.class), eq(11L));
    }

    @Test
    void 报告装配_成员可读_委托core单源() {
        login();
        when(client.buildReport(11L, 77L)).thenReturn(Map.of("verdict", "PASS", "frames", 300));
        assertEquals("PASS", controller.buildReport(11L, 77L).getData().get("verdict"));
        verify(guard).assertCanView(any(CurrentUser.class), eq(11L));
        verify(client).buildReport(11L, 77L);
    }

    @Test
    void 版本对比_两运行入参透传_跨场景人话由core拒() {
        login();
        when(client.compareReports(11L, 77L, 78L)).thenReturn(Map.of("diffRows", List.of()));
        assertEquals(1, controller.compareReports(11L, 77L, 78L).getData().size());
        verify(client).compareReports(11L, 77L, 78L);
    }

    @Test
    void 实时帧与事件_成员可读_运行页轮询取数通道() {
        login();
        when(client.listFrames(11L, 77L)).thenReturn(List.of(Map.of("secondOffset", 1)));
        when(client.listEvents(11L, 77L)).thenReturn(List.of(Map.of("type", "BREAKER")));
        assertEquals(1, controller.listFrames(11L, 77L).getData().size());
        assertEquals(1, controller.listEvents(11L, 77L).getData().size());
        verify(guard, times(2)).assertCanView(any(CurrentUser.class), eq(11L));
    }

    /** Live-curve bridge : the cursor travels to core verbatim and the same
 * view guard wraps the face — the incremental read is an optimization, never an
 * authorization shortcut. */
    @Test
    void 实况曲线桥_游标透传且同视图守卫() {
        login();
        when(client.liveCurve(11L, 77L, 42)).thenReturn(Map.of("lastOffset", 43, "sampled", false));
        Map<String, Object> view = controller.liveCurve(11L, 77L, 42).getData();
        assertEquals(43, view.get("lastOffset"));
        verify(client).liveCurve(11L, 77L, 42);
        verify(guard).assertCanView(any(CurrentUser.class), eq(11L));
    }

    @Test
    void core人话错误_原样透传_admin禁二套() {
        login();
        when(client.createScenario(any())).thenThrow(new ServiceException(
                "压力形态 RAMP 缺少必填参数 rampSeconds，请补全爬坡时长后保存",
                BizErrorCode.VALIDATION_ERROR));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.createScenario(scenarioRequest()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertEquals("压力形态 RAMP 缺少必填参数 rampSeconds，请补全爬坡时长后保存", ex.getMessage());
    }
}
