package com.sixbean.testpike.admin.tool.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.admin.tool.api.dto.ProjectToolBindingItem;
import com.sixbean.testpike.admin.tool.api.dto.ProjectToolBindingRequest;
import com.sixbean.testpike.admin.tool.api.dto.ProjectToolItem;
import com.sixbean.testpike.admin.tool.api.dto.ProjectToolView;
import com.sixbean.testpike.admin.tool.app.component.ToolAccessGuard;
import com.sixbean.testpike.admin.tool.infrastructure.remote.client.ToolClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


class ProjectToolBridgeTest {

    private final ToolClient client = mock(ToolClient.class);
    private final StubCoreProjectClient projectClient = new StubCoreProjectClient();
    private final ProjectAccessGuard projectGuard = new ProjectAccessGuard(projectClient);
    private final ToolAccessGuard guard = new ToolAccessGuard(projectGuard);
    private final ProjectToolController controller = new ProjectToolController(client, guard);
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static CurrentUser login(String... roles) {
        CurrentUser user = new CurrentUser(42L, "u42", "U", "测试人", List.of(roles),
                System.currentTimeMillis() + 3600_000, null);
        UserContext.set(user);
        return user;
    }


    @Test
    void 读取按projectId透传() {
        login("ADMIN");
        ProjectToolView view = new ProjectToolView(List.of(), 0L);
        when(client.projectTools(7L)).thenReturn(view);
        Response<ProjectToolView> resp = controller.list(7L);
        assertSame(view, resp.getData());
        verify(client).projectTools(7L);
    }

    @Test
    void 读取非项目负责人_403且不打core() {
        login("TESTER");
        projectClient.memberRoleResult(0);
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.list(7L));
        assertEquals(40301, ex.getCode());
        verifyNoInteractions(client);
    }


    @Test
    void 保存整对象透传禁字段拷贝() {
        login("TEST_LEAD");
        projectClient.memberRoleResult(2);
        ProjectToolBindingRequest req = new ProjectToolBindingRequest(
                List.of(new ProjectToolBindingItem(9L, 1)), 123L);
        ProjectToolView saved = new ProjectToolView(
                List.of(new ProjectToolItem(9L, 1, "2026-09-11 01:00:00", "取数助手", 2,
                        "可执行类")), 456L);
        when(client.saveProjectTools(eq(7L), same(req))).thenReturn(saved);
        Response<ProjectToolView> resp = controller.save(7L, req);
        ArgumentCaptor<ProjectToolBindingRequest> cap =
                ArgumentCaptor.forClass(ProjectToolBindingRequest.class);
        verify(client).saveProjectTools(eq(7L), cap.capture());
        assertSame(req, cap.getValue(), "必须整对象透传，禁手动逐字段拷贝（2026-08-14 桥接层铁律）");
        assertEquals(456L, resp.getData().version());
        assertEquals("取数助手", resp.getData().list().get(0).name());
    }

    @Test
    void 保存非管理面_403且不打core() {
        login("TESTER");
        projectClient.memberRoleResult(1);
        ProjectToolBindingRequest req = new ProjectToolBindingRequest(List.of(), null);
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.save(7L, req));
        assertEquals(40301, ex.getCode());
        verifyNoInteractions(client);
    }


    @Test
    void 启用回显视图接收core六字段禁静默丢() throws Exception {
        String coreJson = """
                {"list":[{"toolId":9,"enabled":1,"boundAt":"2026-09-11 01:00:00",\
                "name":"取数助手","type":2,"typeLabel":"可执行类"}],"version":456}""";
        ProjectToolView view = mapper.readValue(coreJson, ProjectToolView.class);
        assertEquals(456L, view.version());
        ProjectToolItem item = view.list().get(0);
        assertEquals(9L, item.toolId());
        assertEquals(1, item.enabled());
        assertEquals("2026-09-11 01:00:00", item.boundAt());
        assertEquals("取数助手", item.name());
        assertEquals(2, item.type());
        assertEquals("可执行类", item.typeLabel());
    }

    @Test
    void 保存请求序列化字段名与core契约同名() throws Exception {
        String json = mapper.writeValueAsString(new ProjectToolBindingRequest(
                List.of(new ProjectToolBindingItem(9L, 0)), 123L));
        for (String key : List.of("tools", "expectedVersion", "toolId", "enabled")) {
            assertTrue(json.contains("\"" + key + "\""), "契约字段缺失: " + key);
        }
    }
}
