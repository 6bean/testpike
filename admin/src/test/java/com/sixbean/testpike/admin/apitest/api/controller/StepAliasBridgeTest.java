package com.sixbean.testpike.admin.apitest.api.controller;

import com.sixbean.testpike.admin.apitest.api.dto.StepAliasSaveRequest;
import com.sixbean.testpike.admin.apitest.api.dto.StepAliasUpdateRequest;
import com.sixbean.testpike.admin.apitest.api.dto.StepAliasView;
import com.sixbean.testpike.admin.apitest.infrastructure.remote.client.CoreStepAliasClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class StepAliasBridgeTest {

    private final CoreStepAliasClient client = mock(CoreStepAliasClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final StepAliasController controller = new StepAliasController(client, guard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private void login() {
        UserContext.set(new CurrentUser(7L, "u", "名", "部", List.of("TESTER"), 0L, null));
    }

    private StepAliasView alias(long id, long projectId) {
        return new StepAliasView(id, projectId, "创建订单", "step", "3.orderNo", "req-9");
    }

    @Test
    void 登记_写闸按入参项目_透传核心客户端() {
        login();
        when(client.createAlias(any())).thenReturn(alias(1L, 11L));
        StepAliasSaveRequest request = new StepAliasSaveRequest(11L, "创建订单", "step", "3.orderNo", null);
        StepAliasView view = controller.create(request).getData();
        assertEquals("创建订单", view.aliasName());
        verify(guard).assertCanWriteCase(any(CurrentUser.class), eq(11L));
        verify(client).createAlias(request);
    }

    @Test
    void 登记_非成员403拒_core零下发() {
        login();
        org.mockito.Mockito.doThrow(new ServiceException("无权限", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanWriteCase(any(CurrentUser.class), anyLong());
        assertThrows(ServiceException.class, () -> controller.create(
                new StepAliasSaveRequest(11L, "创建订单", "step", "3.orderNo", null)));
        verify(client, never()).createAlias(any());
    }

    @Test
    void 改指向_归属按core回显_禁信前端传归属() {
        login();
        when(client.findAlias(5L)).thenReturn(alias(5L, 22L));
        when(client.updateAlias(eq(5L), any())).thenReturn(alias(5L, 22L));
        controller.update(5L, new StepAliasUpdateRequest("step", "4.orderNo", null)).getData();
        verify(guard).assertCanWriteCase(any(CurrentUser.class), eq(22L));
        verify(client).updateAlias(eq(5L), any(StepAliasUpdateRequest.class));
    }

    @Test
    void 删除_别名不存在人话拒_写动作不下发() {
        login();
        when(client.findAlias(9L)).thenThrow(new ServiceException(
                "别名不存在（可能已被删除）: 9", BizErrorCode.VALIDATION_ERROR));
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.delete(9L));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        verify(client, never()).deleteAlias(anyLong());
    }

    @Test
    void 列表_查看闸走H1隔离_透传项目参数() {
        login();
        when(client.listAliases(11L)).thenReturn(List.of(alias(1L, 11L)));
        assertEquals(1, controller.list(11L).getData().size());
        verify(guard).assertCanView(any(CurrentUser.class), eq(11L));
        verify(client).listAliases(11L);
    }

    @Test
    void 冲突人话_core文案原样透传_admin禁二套闸() {
        login();
        when(client.createAlias(any())).thenThrow(new ServiceException(
                "别名「3」是纯数字——与步骤号直址写法（{{3.字段}}）冲突，请改用业务名称",
                BizErrorCode.VALIDATION_ERROR));
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.create(
                new StepAliasSaveRequest(11L, "3", "step", "3.orderNo", null)));
        assertEquals(true, ex.getMessage().contains("纯数字"), "人话点名须原样到达前端: " + ex.getMessage());
    }
}
