package com.sixbean.testpike.admin.delreq.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.delreq.api.dto.DeleteRequestCreateRequest;
import com.sixbean.testpike.admin.delreq.api.dto.DeleteRequestRejectRequest;
import com.sixbean.testpike.admin.delreq.api.dto.DeleteRequestView;
import com.sixbean.testpike.admin.delreq.infrastructure.remote.client.DeleteRequestClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * <ul>
 * </ul>
 *
 */
class DeleteRequestBridgeTest {

    private final DeleteRequestClient client = mock(DeleteRequestClient.class);
    private final StubCoreProjectClient projectClient = new StubCoreProjectClient();
    private final ProjectAccessGuard projectGuard = new ProjectAccessGuard(projectClient);
    private final DeleteRequestController controller =
            new DeleteRequestController(client, projectGuard);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static CurrentUser login(long userId, String... roles) {
        CurrentUser user = new CurrentUser(userId, "u" + userId, "U", "测试人", List.of(roles),
                System.currentTimeMillis() + 3600_000, null);
        UserContext.set(user);
        return user;
    }

    private static DeleteRequestView pending(long id) {
        return new DeleteRequestView(id, 1, "用例", 88L, 7L, "TC-0042「登录冒烟」",
                "环境验证完毕", null, 5L, 2, "待审批", null, null,
                LocalDateTime.of(2026, 9, 10, 16, 0));
    }

    @Test
    void 列表项目成员可看_以H1隔离口径透传core() {
        login(9L, "TESTER");
        projectClient.memberRoleResult(1);
        when(client.list(7L, 2, 1, 20, null, null, 9L)).thenReturn(new PageView<>(List.of(pending(3L)), 1L));

        Response<PageView<DeleteRequestView>> resp = controller.list(7L, 2, 1, 20, null, null);

        assertEquals("0", resp.getCode());
        assertEquals(1, resp.getData().list().size());
        verify(client).list(7L, 2, 1, 20, null, null, 9L);
    }

    /** page 18: the sort pair forwards untouched — the bridge
 * owns no second gate; null (absent) and blank (cleared sort) both pass
 * through as given and the core whitelist decides the fallback order. */
    @Test
    void 列表排序对原样透传core_桥层不设第二道闸() {
        login(9L, "TESTER");
        projectClient.memberRoleResult(2);
        when(client.list(7L, null, 1, 20, "status", "asc", 9L))
                .thenReturn(new PageView<>(List.of(pending(3L)), 1L));

        controller.list(7L, null, 1, 20, "status", "asc");
        verify(client).list(7L, null, 1, 20, "status", "asc", 9L);

        controller.list(7L, null, 1, 20, "", "");
        verify(client).list(7L, null, 1, 20, "", "", 9L);
    }

    @Test
    void 列表非项目成员403_零触core() {
        login(9L, "TESTER");
        ServiceException e = assertThrows(ServiceException.class,
                () -> controller.list(7L, null, 1, 20, null, null));
        assertEquals(40301, e.getCode());
        verifyNoInteractions(client);
    }

    @Test
    void 提交申请属操作人登录态透传_请求体原样桥给core() {
        login(9L, "TESTER");
        projectClient.memberRoleResult(2);
        when(client.create(9L, new DeleteRequestCreateRequest(7L, 1, 88L, "环境验证完毕")))
                .thenReturn(pending(3L));
        DeleteRequestCreateRequest request =
                new DeleteRequestCreateRequest(7L, 1, 88L, "环境验证完毕");

        Response<DeleteRequestView> resp = controller.create(request);

        verify(client).create(9L, request);
        assertEquals(3L, resp.getData().id());
    }

    @Test
    void 提交申请开发人员403_只读角色禁删同口径() {
        login(9L, "DEVELOPER");
        projectClient.memberRoleResult(2);
        DeleteRequestCreateRequest request =
                new DeleteRequestCreateRequest(7L, 1, 88L, "想删");

        ServiceException e = assertThrows(ServiceException.class, () -> controller.create(request));
        assertEquals(40301, e.getCode());
        verifyNoInteractions(client);
    }

    @Test
    void 审批通过先查单取projectId再判权_操作人透传() {
        login(9L, "TESTER");
        projectClient.memberRoleResult(2);
        when(client.get(3L, 9L)).thenReturn(pending(3L));
        when(client.approve(3L, 9L)).thenReturn(pending(3L));

        Response<DeleteRequestView> resp = controller.approve(3L);

        assertEquals(2, resp.getData().status(), "桥层零改写：core 返回态原样透出");
        verify(client).get(3L, 9L);
        verify(client).approve(3L, 9L);
    }

    @Test
    void 审批越权成员403_零触core审批链路() {
        login(9L, "TESTER");
        projectClient.memberRoleResult(1);
        when(client.get(3L, 9L)).thenReturn(pending(3L));

        ServiceException e = assertThrows(ServiceException.class, () -> controller.approve(3L));
        assertEquals(40301, e.getCode());
        verify(client).get(3L, 9L);
        verify(client, never()).approve(anyLong(), anyLong());
    }

    @Test
    void 拒绝必填原因桥层不吞core错误() {
        login(1L, "ADMIN");
        when(client.get(3L, 1L)).thenReturn(pending(3L));
        when(client.reject(eq(3L), eq(1L), any(DeleteRequestRejectRequest.class)))
                .thenThrow(new ServiceException("拒绝原因不能为空", 40001));

        ServiceException e = assertThrows(ServiceException.class,
                () -> controller.reject(3L, new DeleteRequestRejectRequest("")));
        assertEquals(40001, e.getCode());
        assertTrue(e.getMessage().contains("拒绝原因"));
    }

    @Test
    void 未登录必401不触core() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> controller.create(new DeleteRequestCreateRequest(7L, 1, 88L, "r")));
        assertEquals(40101, e.getCode());
        verifyNoInteractions(client);
    }

    @Test
    void core视图JSON字段对齐_人话label与时间零静默丢() throws Exception {
        String coreJson = """
                {"id":3,"targetType":1,"targetTypeLabel":"用例","targetId":88,"projectId":7,
                 "targetSummary":"TC-0042「登录冒烟」","reason":"环境验证完毕","rejectReason":null,
                 "applicantId":5,"status":2,"statusLabel":"待审批","approverId":null,
                 "handledAt":null,"createdAt":"2026-09-10T16:00:00"}""";
        DeleteRequestView view = mapper.readValue(coreJson, DeleteRequestView.class);
        assertEquals(1, view.targetType());
        assertEquals("用例", view.targetTypeLabel());
        assertEquals("TC-0042「登录冒烟」", view.targetSummary(), "物化摘要禁静默丢（V44 裁定⑦）");
        assertEquals("待审批", view.statusLabel());
        assertEquals(5L, view.applicantId(), "申请人与审批人字段名跨服务对齐（列表两列依赖）");
        assertEquals(LocalDateTime.of(2026, 9, 10, 16, 0), view.createdAt());
        org.junit.jupiter.api.Assertions.assertNull(view.handledAt(), "待审批 handledAt=null");
    }

    @Test
    void 已批准态label本域语义勘正透出() throws Exception {
        String coreJson = """
                {"id":4,"targetType":1,"targetTypeLabel":"用例","targetId":88,"projectId":7,
                 "targetSummary":"TC-1「a」","reason":"r","rejectReason":null,"applicantId":5,
                 "status":3,"statusLabel":"已批准","approverId":1,"handledAt":"2026-09-10T17:30:00",
                 "createdAt":"2026-09-10T16:00:00"}""";
        DeleteRequestView view = mapper.readValue(coreJson, DeleteRequestView.class);
        assertEquals("已批准", view.statusLabel());
        assertEquals(1L, view.approverId());
    }
}
