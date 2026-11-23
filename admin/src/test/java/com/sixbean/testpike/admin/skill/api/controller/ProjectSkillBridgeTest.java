package com.sixbean.testpike.admin.skill.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.admin.skill.api.dto.ProjectSkillBindingItem;
import com.sixbean.testpike.admin.skill.api.dto.ProjectSkillBindingRequest;
import com.sixbean.testpike.admin.skill.api.dto.ProjectSkillItem;
import com.sixbean.testpike.admin.skill.api.dto.ProjectSkillView;
import com.sixbean.testpike.admin.skill.app.component.SkillAccessGuard;
import com.sixbean.testpike.admin.skill.infrastructure.remote.client.SkillClient;
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


class ProjectSkillBridgeTest {

    private final SkillClient client = mock(SkillClient.class);
    private final StubCoreProjectClient projectClient = new StubCoreProjectClient();
    private final ProjectAccessGuard projectGuard = new ProjectAccessGuard(projectClient);
    private final SkillAccessGuard guard = new SkillAccessGuard(projectGuard);
    private final ProjectSkillController controller = new ProjectSkillController(client, guard);
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
        ProjectSkillView view = new ProjectSkillView(List.of(), 0L);
        when(client.projectSkills(7L)).thenReturn(view);
        Response<ProjectSkillView> resp = controller.list(7L);
        assertSame(view, resp.getData());
        verify(client).projectSkills(7L);
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
        ProjectSkillBindingRequest req = new ProjectSkillBindingRequest(
                List.of(new ProjectSkillBindingItem(9L, 1, 5, 1)), 123L);
        ProjectSkillView saved = new ProjectSkillView(
                List.of(new ProjectSkillItem(9L, 1, 5, 1, "2026-09-10 04:00",
                        "用例设计助手", "用例设计")), 456L);
        when(client.saveProjectSkills(eq(7L), same(req))).thenReturn(saved);
        Response<ProjectSkillView> resp = controller.save(7L, req);
        ArgumentCaptor<ProjectSkillBindingRequest> cap =
                ArgumentCaptor.forClass(ProjectSkillBindingRequest.class);
        verify(client).saveProjectSkills(eq(7L), cap.capture());
        assertSame(req, cap.getValue(), "必须整对象透传，禁手动逐字段拷贝（2026-08-14 桥接层铁律）");
        assertEquals(456L, resp.getData().version());
        assertEquals("用例设计助手", resp.getData().list().get(0).name());
    }

    @Test
    void 保存清空传空集合透传() {
        login("ADMIN");
        ProjectSkillBindingRequest empty = new ProjectSkillBindingRequest(List.of(), null);
        when(client.saveProjectSkills(eq(7L), same(empty)))
                .thenReturn(new ProjectSkillView(List.of(), 789L));
        controller.save(7L, empty);
        verify(client).saveProjectSkills(7L, empty);
    }

    @Test
    void 保存非项目负责人_403且不打core() {
        login("TESTER");
        projectClient.memberRoleResult(1);
        ProjectSkillBindingRequest req = new ProjectSkillBindingRequest(List.of(), null);
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.save(7L, req));
        assertEquals(40301, ex.getCode());
        assertTrue(ex.getMessage().contains("负责人"), () -> "人话: " + ex.getMessage());
        verifyNoInteractions(client);
    }


    @Test
    void 绑定回显视图接收core七字段() throws Exception {
        String coreJson = """
                {"list":[{"skillId":9,"role":1,"priority":5,"enabled":1,
                 "boundAt":"2026-09-10 04:00","name":"用例设计助手","typeLabel":"用例设计"}],
                 "version":1757476800000001}""";
        ProjectSkillView view = mapper.readValue(coreJson, ProjectSkillView.class);
        assertEquals(1757476800000001L, view.version());
        ProjectSkillItem item = view.list().get(0);
        assertEquals(9L, item.skillId());
        assertEquals(1, item.role());
        assertEquals(5, item.priority());
        assertEquals(1, item.enabled());
        assertEquals("2026-09-10 04:00", item.boundAt());
        assertEquals("用例设计助手", item.name());
        assertEquals("用例设计", item.typeLabel());
    }

    @Test
    void 保存请求序列化字段名对齐core且null键不吞() throws Exception {
        String json = mapper.writeValueAsString(new ProjectSkillBindingRequest(null, null));
        assertTrue(json.contains("\"skills\":null"), () -> "json=" + json);
        assertTrue(json.contains("\"expectedVersion\":null"), () -> "json=" + json);

        String itemJson = mapper.writeValueAsString(
                new ProjectSkillBindingItem(9L, null, null, null));
        for (String key : List.of("skillId", "role", "priority", "enabled")) {
            assertTrue(itemJson.contains("\"" + key + "\""), () -> "itemJson=" + itemJson);
        }
    }
}
