package com.sixbean.testpike.admin.project.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.ProjectView;
import com.sixbean.testpike.admin.project.api.dto.UpdateProjectRequest;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;


class ProjectAttributeBridgeTest {

    private final StubCoreProjectClient client = new StubCoreProjectClient();
    private final ProjectAccessGuard guard = new ProjectAccessGuard(client);
    private final ProjectController controller = new ProjectController(client, guard);
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void loginOwner() {
        UserContext.set(new CurrentUser(1L, "admin", "A", "系统管理员", List.of("ADMIN"),
                System.currentTimeMillis() + 3600_000, null));
    }

    @Test
    void update六字段整对象透传core() {
        loginOwner();
        UpdateProjectRequest request = new UpdateProjectRequest(
                "项目A", "zh", "P1", new BigDecimal("100"),
                "TPX", 11L, 12L, "/data/scripts", "/data/recordings", 3, null);
        controller.update(7L, request);
        UpdateProjectRequest sent = client.lastUpdateRequest();
        assertSame(request, sent, "admin 桥必须整对象透传，禁手动 setXxx 逐字段拷贝（丢字段事故模式）");
        assertEquals("TPX", sent.jiraProjectId());
        assertEquals(11L, sent.testOwnerId());
        assertEquals(12L, sent.devOwnerId());
        assertEquals("/data/scripts", sent.scriptStoragePath());
        assertEquals("/data/recordings", sent.recordingStoragePath());
        assertEquals(3, sent.storageMode());
    }

    @Test
    void update携带页面探查开关整对象透传core() {
        loginOwner();
        UpdateProjectRequest request = new UpdateProjectRequest(
                "项目A", "zh", "P1", new BigDecimal("100"),
                "TPX", 11L, 12L, "/data/scripts", "/data/recordings", 3, 1, 0, 50, 1);
        controller.update(7L, request);
        UpdateProjectRequest sent = client.lastUpdateRequest();
        assertSame(request, sent, "admin 桥必须整对象透传，禁手动 setXxx 逐字段拷贝（丢字段事故模式）");
        assertEquals(1, sent.pageInspectEnabled(), "PUT 桥必须携带页面探查开关（core 契约同名对齐）");
        UpdateProjectRequest legacy = new UpdateProjectRequest(
                "n", "zh", "P1", BigDecimal.ONE, "TPX", 1L, 2L, "/s", "/r", 2, 1, 0, 80);
        assertEquals(null, legacy.pageInspectEnabled());
    }

    @Test
    void 请求JSON字段名与core契约对齐() throws Exception {
        UpdateProjectRequest request = new UpdateProjectRequest(
                "n", "zh", "P1", BigDecimal.ONE,
                "TPX", 1L, 2L, "/s", "/r", 2, null);
        Map<?, ?> json = mapper.readValue(mapper.writeValueAsString(request), Map.class);
        for (String key : List.of("name", "defaultLang", "defaultPriority", "budgetMonthly",
                "jiraProjectId", "testOwnerId", "devOwnerId",
                "scriptStoragePath", "recordingStoragePath", "storageMode", "kbEnabled")) {
            assertEquals(true, json.containsKey(key), "PUT 契约字段缺失：" + key);
        }
    }

    @Test
    void 视图反序列化接收core六新字段() throws Exception {
        String coreJson = """
                {"id":7,"name":"项目A","code":"PA","defaultLang":"zh","defaultPriority":"P1",
                 "status":1,"budgetMonthly":100,"createdBy":1,"createdAt":null,"updatedAt":null,"role":2,
                 "jiraProjectId":"TPX","testOwnerId":11,"devOwnerId":12,
                 "scriptStoragePath":"/data/scripts","recordingStoragePath":"/data/recordings","storageMode":3,"kbEnabled":0}""";
        ProjectView view = mapper.readValue(coreJson, ProjectView.class);
        assertEquals("TPX", view.jiraProjectId());
        assertEquals(11L, view.testOwnerId());
        assertEquals(12L, view.devOwnerId());
        assertEquals("/data/scripts", view.scriptStoragePath());
        assertEquals("/data/recordings", view.recordingStoragePath());
        assertEquals(3, view.storageMode());
    }

    @Test
    void Response包装同样透出六新字段() throws Exception {
        String wrapped = """
                {"code":"0","msg":"ok","data":{"id":7,"name":"项目A","code":"PA",
                 "defaultLang":"zh","defaultPriority":"P1","status":1,"budgetMonthly":100,
                 "createdBy":1,"createdAt":null,"updatedAt":null,"role":2,
                 "jiraProjectId":"TPX","testOwnerId":11,"devOwnerId":12,
                 "scriptStoragePath":"/data/scripts","recordingStoragePath":"/data/recordings","storageMode":3}}""";
        Response<ProjectView> resp = mapper.readValue(wrapped,
                mapper.getTypeFactory().constructParametricType(Response.class, ProjectView.class));
        assertEquals("TPX", resp.getData().jiraProjectId());
        assertEquals(3, resp.getData().storageMode());
    }

    @Test
    void 局部编辑允许六字段全空() throws Exception {
        UpdateProjectRequest request = new UpdateProjectRequest(
                "n2", null, null, null, null, null, null, null, null, null, null);
        Map<?, ?> json = mapper.readValue(mapper.writeValueAsString(request), Map.class);
        assertEquals(true, json.containsKey("storageMode"), "storageMode 键必须出现（core 侧 null=1 回退语义）");
        assertEquals(true, json.containsKey("jiraProjectId"));
    }
}
