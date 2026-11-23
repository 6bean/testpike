package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoStepSuggestionView;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AppautoStepHealingBridgeTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final AppautoStepHealingController controller =
            new AppautoStepHealingController(client, guard);

    private AppautoStepSuggestionView suggestion(long id, long projectId) {
        return new AppautoStepSuggestionView(id, projectId, 42L, 900L,
                2, 2, "登录|按钮|text=登录", "vision", "app.tap",
                "{\"contentDesc\":\"登录\"}", "视图树比对：控件 id 已变更",
                180, 640, 1, null, "2026-10-02 03:00:00");
    }

    @Test
    void 建议单列表_参数透传_查看闸按项目成员() {
        when(client.listStepSuggestions(7L, 42L, 1, 50))
                .thenReturn(List.of(suggestion(1L, 7L)));
        List<AppautoStepSuggestionView> got = controller.list(7L, 42L, 1, 50).getData();
        assertEquals(1, got.size());
        assertEquals("vision", got.get(0).source(), "应答字段直传零加工");
        verify(guard).assertCanView(any(), eq(7L));
        verify(client).listStepSuggestions(7L, 42L, 1, 50);
    }

    @Test
    void 列表状态过滤可空_全状态透传core() {
        controller.list(7L, 42L, null, 100);
        verify(client).listStepSuggestions(7L, 42L, null, 100);
    }

    @Test
    void 采纳_写闸按回显归属_乐观锁版本原样透传() {
        when(client.findStepSuggestion(9L)).thenReturn(suggestion(9L, 7L));
        when(client.adoptStepSuggestion(9L, 3)).thenReturn(suggestion(9L, 7L));

        controller.adopt(9L, 3);

        verify(guard).assertCanManage(any(), eq(7L));
        verify(guard).assertCanWriteCase(any(), eq(7L));
        verify(client).adoptStepSuggestion(9L, 3);
    }

    @Test
    void 驳回_写闸按回显归属_禁信前端传归属() {
        when(client.findStepSuggestion(9L)).thenReturn(suggestion(9L, 7L));

        controller.reject(9L);

        verify(guard).assertCanWriteCase(any(), eq(7L));
        verify(client).rejectStepSuggestion(9L);
    }

    @Test
    void 关态采纳core人话403_原样上抛不吞() {
        when(client.findStepSuggestion(9L)).thenReturn(suggestion(9L, 7L));
        when(client.adoptStepSuggestion(9L, 3)).thenThrow(new ServiceException(
                "步骤自愈采纳开关未开启（当前=关）：AI 候选修复不可写入用例脚本，"
                        + "如需人批落版请到「系统设置 → APP 自动化」开启 ai.appauto.step-healing",
                BizErrorCode.FORBIDDEN));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.adopt(9L, 3));
        assertTrue(ex.getMessage().contains("系统设置"), "core 开关键位指引文案禁加工禁吞");
    }

    @Test
    void 陈旧版本core人话409_带当前版本可重试语义透传() {
        when(client.findStepSuggestion(9L)).thenReturn(suggestion(9L, 7L));
        when(client.adoptStepSuggestion(9L, 1)).thenThrow(new ServiceException(
                "脚本已被他人修改，请刷新后重试（当前版本 4）", BizErrorCode.VERSION_CONFLICT));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.adopt(9L, 1));
        assertTrue(ex.getMessage().contains("当前版本 4"), "重试指引单点在 core，admin 零二套");
    }

    @Test
    void 建议单不存在_查看闸不触发即404透传() {
        when(client.findStepSuggestion(404L)).thenThrow(new ServiceException(
                "自愈建议单不存在（id=404）", BizErrorCode.NOT_FOUND));

        assertThrows(ServiceException.class, () -> controller.reject(404L));
        verify(guard, never()).assertCanView(any(), any(Long.class));
    }
}
