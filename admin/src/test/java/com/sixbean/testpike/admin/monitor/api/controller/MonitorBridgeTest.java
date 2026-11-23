package com.sixbean.testpike.admin.monitor.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.monitor.api.dto.MonitorLinkedAssetsView;
import com.sixbean.testpike.admin.monitor.api.dto.MonitorTargetView;
import com.sixbean.testpike.admin.monitor.api.dto.SilenceRequest;
import com.sixbean.testpike.admin.monitor.api.dto.TargetSaveRequest;
import com.sixbean.testpike.admin.monitor.infrastructure.remote.client.CoreMonitorClient;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class MonitorBridgeTest {

    private final CoreMonitorClient client = mock(CoreMonitorClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final MonitorTargetController controller = new MonitorTargetController(client, guard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private void login() {
        UserContext.set(new CurrentUser(7L, "u", "名", "部", List.of("TESTER"), 0L, null));
    }

    private MonitorTargetView target(long id, long projectId) {
        return new MonitorTargetView(id, projectId, 20L, "官网首页存活", "HTTP",
                "{\"url\":\"http://x\"}", 60, true, null, null, "UP",
                null, null, 0, 0);
    }

    @Test
    void 列表_查看闸按项目_透传core() {
        login();
        when(client.listTargets(11L)).thenReturn(List.of(target(1L, 11L)));
        assertEquals(1, controller.list(11L).getData().size());
        verify(guard).assertCanView(any(CurrentUser.class), eq(11L));
        verify(client).listTargets(11L);
    }

    @Test
    void 非成员查看_403拒_core零下发() {
        login();
        doThrow(new ServiceException("无权限", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanView(any(CurrentUser.class), anyLong());
        assertThrows(ServiceException.class, () -> controller.list(11L));
        verify(client, never()).listTargets(anyLong());
    }

    @Test
    void 建目标_写闸按入参项目_透传() {
        login();
        TargetSaveRequest request = new TargetSaveRequest(11L, 20L, "官网首页存活",
                "HTTP", Map.of("url", "http://x"), 60, true);
        when(client.createTarget(request)).thenReturn(5L);
        assertEquals(5L, controller.create(request).getData());
        verify(guard).assertCanManage(any(CurrentUser.class), eq(11L));
    }

    @Test
    void 改目标_归属按core回显_禁信前端传归属() {
        login();
        when(client.findTarget(3L)).thenReturn(target(3L, 22L));
        TargetSaveRequest request = new TargetSaveRequest(11L, 20L, "改名",
                "HTTP", Map.of("url", "http://x"), 60, true);
        controller.update(3L, request);
        verify(guard).assertCanManage(any(CurrentUser.class), eq(22L));
        verify(client).updateTarget(eq(3L), eq(request));
    }

    @Test
    void 启停_写闸按回显_不存在人话拒不下发() {
        login();
        when(client.findTarget(9L)).thenThrow(new ServiceException("监控目标不存在", BizErrorCode.NOT_FOUND));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.toggle(9L, true));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        verify(client, never()).updateEnabled(anyLong(), anyBoolean());
    }

    @Test
    void 删除_写闸按回显_透传() {
        login();
        when(client.findTarget(3L)).thenReturn(target(3L, 22L));
        controller.delete(3L);
        verify(guard).assertCanManage(any(CurrentUser.class), eq(22L));
        verify(client).deleteTarget(3L);
    }

    @Test
    void 静默_写闸按回显_理由透传core单点校验() {
        login();
        when(client.findTarget(3L)).thenReturn(target(3L, 22L));
        SilenceRequest request = new SilenceRequest(30, "版本发布窗口");
        controller.silence(3L, request);
        verify(client).silence(eq(3L), eq(request));
        doThrow(new ServiceException("静默必须填写理由（进审计，如「版本发布窗口」）",
                BizErrorCode.VALIDATION_ERROR)).when(client).silence(anyLong(), any());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.silence(3L, new SilenceRequest(30, null)));
        assertTrue(ex.getMessage().contains("静默必须填写理由"), "core 人话须原样到达前端");
    }

    @Test
    void 探测历史_查看闸按回显_limit透传钳制单点core() {
        login();
        when(client.findTarget(3L)).thenReturn(target(3L, 22L));
        controller.checkLogs(3L, 500);
        verify(guard).assertCanView(any(CurrentUser.class), eq(22L));
        verify(client).checkLogs(3L, 500);
    }

    @Test
    void 环境进行中事件_查看闸_空值透传() {
        login();
        when(client.openEpisode(20L)).thenReturn(null);
        assertEquals(null, controller.openEpisode(11L, 20L).getData());
        verify(guard).assertCanView(any(CurrentUser.class), eq(11L));
    }

    @Test
    void 关联资产_查看闸_透传() {
        login();
        when(client.linkedAssets(20L)).thenReturn(new MonitorLinkedAssetsView(
                20L, 1, List.of(" nightly 计划"), 0, List.of(), 2, List.of("造数A", "造数B"),
                0, List.of()));
        var view = controller.linkedAssets(11L, 20L).getData();
        assertEquals(2, view.factoryCount());
        verify(guard).assertCanView(any(CurrentUser.class), eq(11L));
        verify(client).linkedAssets(20L);
    }
}
