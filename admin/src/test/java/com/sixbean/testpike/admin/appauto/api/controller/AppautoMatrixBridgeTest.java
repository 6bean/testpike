package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixCombo;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixDispatchResult;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixFanoutRequest;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixModelRow;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixPrecheckView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixSampleRequest;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixSampleView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixStepFailRow;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AppautoMatrixBridgeTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final AppautoMatrixController controller = new AppautoMatrixController(client, guard);

    private AppautoMatrixFanoutRequest request() {
        return new AppautoMatrixFanoutRequest(7L, 42L, "机型矩阵批", 9L,
                List.of(new AppautoMatrixCombo("MOBILEWRIGHT", "ANDROID", "EMULATOR", null)));
    }

    @Test
    void 预检_参数透传_查看闸按项目() {
        when(client.matrixPrecheck(any())).thenReturn(new AppautoMatrixPrecheckView(
                2, 5, 3, 2, 10, "2026-10-05 23:00", 12, false,
                "矩阵扇出预检：预计 2 台 × 5 分钟"));
        AppautoMatrixPrecheckView got = controller.matrixPrecheck(request()).getData();
        assertEquals(2, got.fanoutTasks());
        assertEquals(12, got.fanoutMaxAllowed(), "上限=core K8 现读投影原样，禁 admin 二次判");
        assertTrue(got.message().contains("预计 2 台"), "人话文案原样直传");
        verify(guard).assertCanView(any(), eq(7L));
    }

    @Test
    void 扇出_派发结果透传_消耗闸加查看闸() {
        when(client.matrixFanout(any())).thenReturn(new AppautoMatrixDispatchResult(
                100L, "B-abc12345", 2, List.of(1L, 2L), "机型矩阵已扇出 2 个执行任务"));
        AppautoMatrixDispatchResult got = controller.matrixFanout(request()).getData();
        assertEquals(100L, got.batchId());
        assertEquals(2, got.taskIds().size(), "任务 id 清单按派发序原样透传");
        assertTrue(got.message().contains("已扇出 2 个"), "core 人话直传");
        verify(guard).assertCanView(any(), eq(7L));
        verify(client).matrixFanout(any());
    }

    @Test
    void 报告_机型行透传_admin_禁重算通过率() {
        when(client.matrixModelReport(7L, 42L)).thenReturn(List.of(
                new AppautoMatrixModelRow("Mi13", "14", 2, 0, 2, 0.0,
                        List.of(new AppautoMatrixStepFailRow(4, "app.tap", 2, "弹窗拦截：隐私协议未处理")),
                        "Mi13（OS 14）失败集中在第 4 步 app.tap（弹窗拦截：隐私协议未处理，共 2 次）")));
        List<AppautoMatrixModelRow> rows = controller.matrixModelReport(7L, 42L).getData();
        assertEquals(1, rows.size());
        assertEquals(0.0, rows.get(0).passRate(), 0.0001, "通过率=core 实算值原样，禁 admin 重算");
        assertEquals("弹窗拦截：隐私协议未处理",
                rows.get(0).stepFailures().get(0).sampleDetail(), "归因样例原样直传");
        verify(guard).assertCanView(any(), eq(7L));
    }

    @Test
    void 抽样_推荐视图透传_消耗闸加查看闸_关态人话直传() {
        when(client.matrixSample(any())).thenReturn(new AppautoMatrixSampleView(
                List.of(new AppautoMatrixCombo("MOBILEWRIGHT", "ANDROID", "EMULATOR", null)),
                "候选组合 8 个（其中可寻址设备档案 5 个）", "推荐 1 组合 × 每任务约 5 分钟", "优先成熟组合"));
        AppautoMatrixSampleView got = controller.matrixSample(
                new AppautoMatrixSampleRequest(7L, 42L, 3)).getData();
        assertEquals(1, got.recommendations().size(), "推荐组合行原样透传禁 admin 二次加工");
        assertTrue(got.coverageMessage().contains("候选组合"), "覆盖率画像句 core 直传");
        assertTrue(got.costMessage().contains("排队") || got.costMessage().contains("分钟"),
                "成本画像句 core 直传");
        verify(guard).assertCanView(any(), eq(7L));
        verify(client).matrixSample(any());
    }
}
