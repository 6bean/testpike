package com.sixbean.testpike.admin.perf.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.perf.api.dto.PerfApprovalApplyRequest;
import com.sixbean.testpike.admin.perf.api.dto.PerfApprovalRejectRequest;
import com.sixbean.testpike.admin.perf.api.dto.PerfApproveRequest;
import com.sixbean.testpike.admin.perf.api.dto.PerfGateCheckRequest;
import com.sixbean.testpike.admin.perf.api.dto.PerfGateDecisionView;
import com.sixbean.testpike.admin.perf.api.dto.PerfGateStepView;
import com.sixbean.testpike.admin.perf.api.dto.PerfWhitelistSaveRequest;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class PerfGateBridgeTest {

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

    private Map<String, Object> approvalRow(long id) {
        return Map.of("id", id, "status", 2);
    }

    @Test
    void 白名单列表_管理闸按项目_透传core() {
        login();
        when(client.listWhitelist(11L)).thenReturn(List.of(Map.of("id", 1L, "env_id", 20L)));
        assertEquals(1, controller.listWhitelist(11L).getData().size());
        verify(guard).assertCanManage(any(CurrentUser.class), eq(11L));
        verify(client).listWhitelist(11L);
    }

    @Test
    void 白名单新增_管理闸按入参项目_id透传() {
        login();
        when(client.createWhitelist(any())).thenReturn(88L);
        PerfWhitelistSaveRequest request = new PerfWhitelistSaveRequest(11L, 20L, 200, List.of("SHADOW_TABLE"));
        assertEquals(88L, controller.createWhitelist(request).getData());
        verify(guard).assertCanManage(any(CurrentUser.class), eq(11L));
        verify(client).createWhitelist(request);
    }

    @Test
    void 白名单删除_管理闸先行_非管理者core零下发() {
        login();
        doThrow(new ServiceException("该操作仅项目负责人可执行", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanManage(any(CurrentUser.class), anyLong());
        assertThrows(ServiceException.class, () -> controller.deleteWhitelist(11L, 5L));
        verify(client, never()).deleteWhitelist(anyLong(), anyLong());
    }

    @Test
    void 审批申请_申请人取自登录态_经core透传() {
        login();
        when(client.applyApproval(any())).thenReturn(66L);
        PerfApprovalApplyRequest request =
                new PerfApprovalApplyRequest(11L, 20L, 500, "NS_ACCOUNT", "容量验收窗口");
        assertEquals(66L, controller.applyApproval(request).getData());
        verify(guard).assertCanManage(any(CurrentUser.class), eq(11L));
        verify(client).applyApproval(request);
    }

    @Test
    void 审批通过_单据归属经core清单权威核对() {
        login();
        when(client.listApprovals(11L)).thenReturn(List.of(approvalRow(5L)));
        controller.approveApproval(5L, new PerfApproveRequest(11L));
        verify(guard).assertCanManage(any(CurrentUser.class), eq(11L));
        verify(client).approveApproval(eq(5L), eq(7L));
    }

    @Test
    void 审批通过_单据不属于该项目_拒且core零下发() {
        login();
        when(client.listApprovals(11L)).thenReturn(List.of(approvalRow(9L)));
        assertThrows(ServiceException.class,
                () -> controller.approveApproval(5L, new PerfApproveRequest(11L)));
        verify(client, never()).approveApproval(anyLong(), anyLong());
    }

    @Test
    void 审批拒绝_理由透传_归属核对先行() {
        login();
        when(client.listApprovals(11L)).thenReturn(List.of(approvalRow(5L)));
        controller.rejectApproval(5L, new PerfApprovalRejectRequest(11L, "窗口与发布冲突"));
        verify(client).rejectApproval(eq(5L), eq(7L), eq("窗口与发布冲突"));
    }

    @Test
    void 审批列表_管理闸_H1项目隔离内() {
        login();
        when(client.listApprovals(11L)).thenReturn(List.of(approvalRow(5L), approvalRow(6L)));
        assertEquals(2, controller.listApprovals(11L).getData().size());
        verify(guard).assertCanManage(any(CurrentUser.class), eq(11L));
    }

    @Test
    void 开工预检_成员可发起_判定结果原样透出() {
        login();
        when(client.precheck(any())).thenReturn(new PerfGateDecisionView(false,
                "GLOBAL_SWITCH", "生产压测总开关未开启，请联系管理员在系统配置中开启",
                List.of(new PerfGateStepView("GLOBAL_SWITCH", "生产全局开关", "FAIL",
                        "生产压测全局开关未开启（默认关）", "系统管理员",
                        "/system/configs 键 perf.production-enabled")),
                300L, 10, 360_000L));
        PerfGateCheckRequest request =
                new PerfGateCheckRequest(11L, 20L, 100, "SHADOW_TABLE", "https://api.example.com",
                        "CONSTANT_RPS", java.util.Map.of("targetRps", 100, "durationSeconds", 300));
        PerfGateDecisionView decision = controller.precheck(request).getData();
        assertEquals(false, decision.passed());
        assertEquals("GLOBAL_SWITCH", decision.gate());
        // CF-8: the explainable ladder rides through the bridge untouched.
        assertEquals(1, decision.steps().size());
        assertEquals("FAIL", decision.steps().get(0).state());
        // U-5: the server-computed budget preview rides through the bridge untouched.
        assertEquals(300L, decision.runDurationSeconds());
        assertEquals(10, decision.budgetMarginMinutes());
        assertEquals(360_000L, decision.budgetMs());
        assertEquals("系统管理员", decision.steps().get(0).fixBy());
        verify(guard).assertCanView(any(CurrentUser.class), eq(11L));
    }

    @Test
    void core人话错误_原样透传_admin禁二套() {
        login();
        when(client.createWhitelist(any()))
                .thenThrow(new ServiceException("该环境已备案过压测档位，请直接修改并发上限而不是重复新增",
                        BizErrorCode.RESOURCE_CONFLICT));
        PerfWhitelistSaveRequest request = new PerfWhitelistSaveRequest(11L, 20L, 200, List.of("NS_ACCOUNT"));
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.createWhitelist(request));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode());
        assertEquals("该环境已备案过压测档位，请直接修改并发上限而不是重复新增", ex.getMessage());
    }

    @Test
    void 未登录_操作人缺失_申请与审批拒() {
        PerfApprovalApplyRequest request =
                new PerfApprovalApplyRequest(11L, 20L, 500, "NS_ACCOUNT", "理由");
        assertThrows(ServiceException.class, () -> controller.applyApproval(request));
        assertThrows(ServiceException.class,
                () -> controller.approveApproval(5L, new PerfApproveRequest(11L)));
        verify(client, never()).applyApproval(any());
        verify(client, never()).approveApproval(anyLong(), anyLong());
    }

    @Test
    void engineCatalogue_isBridgedVerbatim() {
        java.util.List<java.util.Map<String, Object>> rows = java.util.List.of(
                new java.util.LinkedHashMap<>(java.util.Map.of("engine", "GATLING")),
                rowWithNullCaveat("JMETER"));
        when(client.engines()).thenReturn(rows);
        assertEquals(rows, controller.listEngines().getData());
        verify(client).engines();
    }

    @Test
    void engineCatalogue_bridgeKeepsHonestAbsenceRows() {
        java.util.Map<String, Object> k6 = rowWithNullCaveat("K6");
        when(client.engines()).thenReturn(java.util.List.of(k6));
        java.util.Map<String, Object> bridged = controller.listEngines().getData().get(0);
        assertEquals("K6", bridged.get("engine"));
        org.junit.jupiter.api.Assertions.assertNull(bridged.get("caveat"),
                "a null caveat is an honest no-claim and must survive the bridge untouched");
    }

    private static java.util.Map<String, Object> rowWithNullCaveat(String engine) {
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("engine", engine);
        row.put("caveat", null);
        return row;
    }
}
