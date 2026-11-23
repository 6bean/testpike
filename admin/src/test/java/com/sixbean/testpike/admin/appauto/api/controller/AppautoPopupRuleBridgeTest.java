package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoPopupRuleView;
import com.sixbean.testpike.admin.appauto.api.dto.PopupRuleLearnRequest;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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


class AppautoPopupRuleBridgeTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final AppautoPopupRuleController controller =
            new AppautoPopupRuleController(client, guard);

    private AppautoPopupRuleView ruleRow(long id, long projectId, int status) {
        return new AppautoPopupRuleView(id, projectId, 903L, "隐私弹窗-同意按钮",
                "{\"detect\":{\"textContains\":\"用户隐私政策\"},\"dismiss\":{\"action\":\"app.tap\","
                        + "\"target\":{\"text\":\"同意\"}}}",
                status, "AI", "2026-10-06 07:00:00");
    }

    @Test
    void 发起自学_查看闸透传_应答草稿号原样() {
        when(client.learnPopupRule(903L)).thenReturn(42L);
        long got = controller.learn(new PopupRuleLearnRequest(903L, 7L)).getData();
        assertEquals(42L, got, "草稿号原样透传（人批准门入口锚）");
        verify(guard).assertCanView(any(), eq(7L));
        verify(client).learnPopupRule(903L);
    }

    @Test
    void 列举_查看闸按项目_异项目行不出桥() {
        when(client.listPopupRules(7L, null, 100))
                .thenReturn(List.of(ruleRow(1L, 7L, 1), ruleRow(2L, 8L, 1)));
        List<AppautoPopupRuleView> rows = controller.list(7L, null, 100).getData();
        verify(guard).assertCanView(any(), eq(7L));
        assertEquals(1, rows.size(), "授权项目外行必须剔除（防他项目号换越权列表）");
        assertEquals(1L, rows.get(0).id());
    }

    @Test
    void 批准_管理闸按core回显归属_状态由core回显() {
        when(client.findPopupRule(1L)).thenReturn(ruleRow(1L, 7L, 1));
        when(client.confirmPopupRule(1L)).thenReturn(ruleRow(1L, 7L, 2));
        AppautoPopupRuleView got = controller.confirm(1L).getData();
        assertEquals(2, got.status(), "入库态由 core 回显（人批唯一通道）");
        verify(guard).assertCanManage(any(), eq(7L));
    }

    @Test
    void 驳回_管理闸按core回显归属_已处理冲突人话上抛不吞() {
        when(client.findPopupRule(1L)).thenReturn(ruleRow(1L, 7L, 2));
        Mockito.doThrow(new ServiceException("该规则草稿已入库，不可重复驳回（请刷新查看最新状态）",
                BizErrorCode.RESOURCE_CONFLICT))
                .when(client).rejectPopupRule(1L);
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.reject(1L));
        assertTrue(ex.getMessage().contains("已入库"), "core 人话文案禁加工禁吞");
        verify(guard).assertCanManage(any(), eq(7L));
    }

    @Test
    void 单读_非项目成员拒_不吞不越权() {
        when(client.findPopupRule(1L)).thenReturn(ruleRow(1L, 8L, 1));
        Mockito.doThrow(new ServiceException("无权查看该项目", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanView(any(), eq(8L));
        assertThrows(ServiceException.class, () -> controller.get(1L));
        verify(client, never()).confirmPopupRule(1L);
    }
}
