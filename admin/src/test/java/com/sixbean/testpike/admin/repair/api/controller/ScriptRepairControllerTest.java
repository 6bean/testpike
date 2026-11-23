package com.sixbean.testpike.admin.repair.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cases.app.component.CaseProjectResolver;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.repair.api.dto.ScriptRepairView;
import com.sixbean.testpike.admin.repair.infrastructure.remote.client.ScriptRepairClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


class ScriptRepairControllerTest {

    private final ScriptRepairClient client = mock(ScriptRepairClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final CaseProjectResolver caseProjectResolver = mock(CaseProjectResolver.class);
    private final ScriptRepairController controller =
            new ScriptRepairController(client, guard, caseProjectResolver);
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static void login(long userId, List<String> roles) {
        UserContext.set(new CurrentUser(userId, "u" + userId, "U", "测试",
                roles, System.currentTimeMillis() + 3600_000, null));
    }

    private static ScriptRepairView viewFixture() {
        return new ScriptRepairView(1L, 3L, 9L, 20L, 21L, 2,
                "定位器失配：提交按钮 id 变更，提案更新为新 id", 1, "已修复", null,
                "2026-09-26 15:00:00");
    }

    @Test
    void projectId分页读路径_成员闸先过_筛选与分页按位透传core() {
        login(7L, List.of("TESTER"));
        when(client.list(3L, null, 1, 20)).thenReturn(new PageView<>(List.of(viewFixture()), 1L));
        Response<PageView<ScriptRepairView>> resp = controller.list(3L, null, 1, 20);
        assertEquals("0", resp.getCode());
        assertEquals(1, resp.getData().list().size());
        ArgumentCaptor<CurrentUser> user = ArgumentCaptor.forClass(CurrentUser.class);
        verify(guard).assertCanView(user.capture(), eq(3L));
        assertEquals(7L, user.getValue().userId(), "守卫必须用当前登录人（禁拿路径参数冒充）");
        verify(client).list(3L, null, 1, 20);
    }

    @Test
    void 仅caseId读路径_反查项目归属后过闸_用例详情面板入口可用() {
        login(7L, List.of("TESTER"));
        when(caseProjectResolver.resolveProjectId(9L)).thenReturn(3L);
        when(client.list(3L, 9L, 1, 20)).thenReturn(new PageView<>(List.of(viewFixture()), 1L));
        Response<PageView<ScriptRepairView>> resp = controller.list(null, 9L, 1, 20);
        assertEquals("0", resp.getCode());
        verify(guard).assertCanView(any(CurrentUser.class), eq(3L));
        verify(client).list(3L, 9L, 1, 20);
    }

    @Test
    void caseId项目缺失core人话404透传_不吞不造假项目() {
        login(7L, List.of("TESTER"));
        when(caseProjectResolver.resolveProjectId(99L))
                .thenThrow(new ServiceException("用例不存在", BizErrorCode.NOT_FOUND));
        assertThrows(ServiceException.class, () -> controller.list(null, 99L, 1, 20));
        verifyNoInteractions(client);
    }

    @Test
    void 无筛选跨项目总览_非系统管理员403_零桥交互() {
        login(7L, List.of("TESTER"));
        when(guard.isSystemManager(any(CurrentUser.class))).thenReturn(false);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.list(null, null, 1, 20));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        verifyNoInteractions(client);
    }

    @Test
    void 无筛选系统管理员_双空透传core总览() {
        login(1L, List.of("ADMIN"));
        when(guard.isSystemManager(any(CurrentUser.class))).thenReturn(true);
        when(client.list(isNull(), isNull(), eq(1), eq(50)))
                .thenReturn(new PageView<>(List.of(viewFixture()), 12L));
        Response<PageView<ScriptRepairView>> resp = controller.list(null, null, 1, 50);
        assertEquals("0", resp.getCode());
        assertEquals(12L, resp.getData().total());
        verifyNoInteractions(caseProjectResolver);
    }

    @Test
    void core修复记录JSON十字段对齐_人话标签与提案说明零静默丢() throws Exception {
        String coreJson = """
                {"id":1,"projectId":3,"caseId":9,"sourceTaskId":20,"trialTaskId":21,
                 "round":2,"proposalSummary":"定位器失配：提交按钮 id 变更，提案更新为新 id",
                 "outcome":1,"outcomeLabel":"已修复","failNote":null,
                 "createdAt":"2026-09-26 15:00:00"}""";
        ScriptRepairView view = mapper.readValue(coreJson, ScriptRepairView.class);
        assertEquals(1L, view.id());
        assertEquals(3L, view.projectId());
        assertEquals(9L, view.caseId());
        assertEquals(20L, view.sourceTaskId());
        assertEquals(21L, view.trialTaskId());
        assertEquals(2, view.round(), "轮次静默丢=24h 轮次硬闸不可读");
        assertEquals("定位器失配：提交按钮 id 变更，提案更新为新 id", view.proposalSummary(),
                "提案说明静默丢=面板原因列空");
        assertEquals(1, view.outcome());
        assertEquals("已修复", view.outcomeLabel(),
                "结局人话标签静默丢=前端二次映射漂移（展示源单点纪律失效）");
        assertNull(view.failNote());
        assertEquals("2026-09-26 15:00:00", view.createdAt(),
                "createdAt String 契约（禁 LocalDateTime 漂移）");
    }
}
