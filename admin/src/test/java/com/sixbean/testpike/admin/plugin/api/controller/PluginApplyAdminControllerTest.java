package com.sixbean.testpike.admin.plugin.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.plugin.api.dto.AppliedPluginView;
import com.sixbean.testpike.admin.plugin.api.dto.PluginApplyInput;
import com.sixbean.testpike.admin.plugin.api.dto.PluginApplyView;
import com.sixbean.testpike.admin.plugin.infrastructure.remote.client.CorePluginClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


class PluginApplyAdminControllerTest {

    private final CorePluginClient client = mock(CorePluginClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final PluginApplyAdminController controller = new PluginApplyAdminController(client, guard);
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static void login(long userId) {
        UserContext.set(new CurrentUser(userId, "u" + userId, "U", "管理员",
                List.of("ADMIN"), System.currentTimeMillis() + 3600_000, null));
    }

    private static PluginApplyView viewFixture() {
        return new PluginApplyView(3L, 9L, "pos-demo",
                Map.of("timeout", 30), List.of("pos-skill"),
                List.of("H7 内容包未就绪，装载跳过（如实标注）"));
    }

    @Test
    void apply链路_负责人闸先过_参数与操作人按位透传core() {
        login(7L);
        when(client.apply(eq(7L), eq(3L), eq(9L), any(PluginApplyInput.class)))
                .thenReturn(viewFixture());
        Response<PluginApplyView> resp = controller.apply(3L, 9L, new PluginApplyInput(true));
        assertEquals("0", resp.getCode());
        ArgumentCaptor<CurrentUser> user = ArgumentCaptor.forClass(CurrentUser.class);
        verify(guard).assertCanManage(user.capture(), eq(3L));
        assertEquals(7L, user.getValue().userId(), "守卫必须用当前登录人（禁拿路径参数冒充）");
        verify(client).apply(7L, 3L, 9L, new PluginApplyInput(true));
    }

    @Test
    void 空body归一confirm_false() {
        login(7L);
        when(client.apply(eq(7L), eq(3L), eq(9L), any(PluginApplyInput.class)))
                .thenReturn(viewFixture());
        controller.apply(3L, 9L, null);
        verify(client).apply(7L, 3L, 9L, new PluginApplyInput(false));
    }

    @Test
    void 越权403先拒_不打core桥() {
        login(7L);
        doThrow(new ServiceException("该操作仅项目负责人可执行", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanManage(any(CurrentUser.class), eq(3L));
        assertThrows(ServiceException.class,
                () -> controller.apply(3L, 9L, new PluginApplyInput(false)));
        verifyNoInteractions(client);
    }

    @Test
    void core装配视图JSON字段对齐_notes零静默丢() throws Exception {
        String coreJson = """
                {"projectId":3,"pluginId":9,"pluginName":"pos-demo",
                 "paramSnapshot":{"timeout":30},
                 "mountedSkills":["pos-skill"],
                 "notes":["H7 内容包未就绪，装载跳过（如实标注）","SKILL 不存在，跳过挂载：ghost"]}""";
        PluginApplyView view = mapper.readValue(coreJson, PluginApplyView.class);
        assertEquals(3L, view.projectId());
        assertEquals(9L, view.pluginId());
        assertEquals("pos-demo", view.pluginName());
        assertEquals(1, view.paramSnapshot().size(), "参数模板快照静默丢=装配记录不可查（IT-H8-02）");
        assertEquals(List.of("pos-skill"), view.mountedSkills());
        assertEquals(2, view.notes().size(), "notes 静默丢=非静默纪律失效（坑67⑤）");
    }

    @Test
    void uninstall链路_负责人闸先过_操作人与双id按位透传core() {
        login(7L);
        Response<Void> resp = controller.uninstall(3L, 9L);
        assertEquals("0", resp.getCode());
        var user = ArgumentCaptor.forClass(CurrentUser.class);
        verify(guard).assertCanManage(user.capture(), eq(3L));
        assertEquals(7L, user.getValue().userId(), "守卫必须用当前登录人（禁拿路径参数冒充）");
        verify(client).uninstall(7L, 3L, 9L);
    }

    @Test
    void uninstall越权403先拒_不打core桥() {
        login(7L);
        doThrow(new ServiceException("该操作仅项目负责人可执行", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanManage(any(CurrentUser.class), eq(3L));
        assertThrows(ServiceException.class, () -> controller.uninstall(3L, 9L));
        verifyNoInteractions(client);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Test
    void listApplied链路_成员闸先过_操作人与projectId按位透传core() {
        login(7L);
        when(client.listApplied(7L, 3L)).thenReturn(List.of(
                new AppliedPluginView(9L, "pos-demo", true,
                        Map.of("region", "TW"), "2026-09-15 12:00:00")));
        Response<List<AppliedPluginView>> resp = controller.listApplied(3L);
        assertEquals("0", resp.getCode());
        assertEquals(1, resp.getData().size());
        var user = ArgumentCaptor.forClass(CurrentUser.class);
        verify(guard).assertCanView(user.capture(), eq(3L));
        assertEquals(7L, user.getValue().userId(), "守卫必须用当前登录人（禁拿路径参数冒充）");
        verify(client).listApplied(7L, 3L);
    }

    @Test
    void listApplied无权限403先拒_不打core桥() {
        login(7L);
        doThrow(new ServiceException("无权限查看该项目", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanView(any(CurrentUser.class), eq(3L));
        assertThrows(ServiceException.class, () -> controller.listApplied(3L));
        verifyNoInteractions(client);
    }

    @Test
    void core已装配视图JSON字段对齐_五字段零静默丢() throws Exception {
        String coreJson = """
                {"pluginId":9,"pluginName":"pos-demo","enabled":true,
                 "paramSnapshot":{"region":"TW","terminal":"T01"},
                 "appliedAt":"2026-09-15 12:00:00"}""";
        AppliedPluginView view = mapper.readValue(coreJson, AppliedPluginView.class);
        assertEquals(9L, view.pluginId());
        assertEquals("pos-demo", view.pluginName());
        assertEquals(true, view.enabled());
        assertEquals(Map.of("region", "TW", "terminal", "T01"), view.paramSnapshot(),
                "参数快照静默丢=前端装配卡参数区空（IT-H8-02 可查语义失效）");
        assertEquals("2026-09-15 12:00:00", view.appliedAt(),
                "appliedAt String 契约（禁 LocalDateTime 漂移）");
    }
}
