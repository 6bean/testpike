package com.sixbean.testpike.admin.skill.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.admin.skill.api.dto.SkillRecycleView;
import com.sixbean.testpike.admin.skill.app.component.SkillAccessGuard;
import com.sixbean.testpike.admin.skill.infrastructure.remote.client.SkillClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


class SkillRecycleBridgeTest {

    private final SkillClient client = mock(SkillClient.class);
    private final StubCoreProjectClient projectClient = new StubCoreProjectClient();
    private final ProjectAccessGuard projectGuard = new ProjectAccessGuard(projectClient);
    private final SkillAccessGuard guard = new SkillAccessGuard(projectGuard);
    private final SkillController controller = new SkillController(client, guard, projectGuard);
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

    private static SkillRecycleView recycleRow(Long projectId) {
        return new SkillRecycleView(7L, projectId, "回归技能", 1, 1, 3, 5,
                42L, "测试人", LocalDateTime.of(2026, 9, 19, 10, 0));
    }


    @Test
    void 回收站列表四参按位透传含项目筛选() {
        login(42L, "TEST_LEAD");
        projectClient.memberRoleResult(2);
        when(client.recycleBin(1L, "技能", 2, 10)).thenReturn(new PageView<>(List.of(), 0L));
        controller.recycleBin(1L, "技能", 2, 10);
        verify(client).recycleBin(1L, "技能", 2, 10);
    }

    @Test
    void 系统级回收站视图非ADMIN403不打core() {
        login(42L, "TESTER");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.recycleBin(null, null, 1, 20));
        assertEquals(40301, ex.getCode());
        verifyNoInteractions(client);
    }

    @Test
    void 项目回收站非成员403不打core() {
        login(42L, "ADMIN_NOT");
        projectClient.crossProject(1L);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.recycleBin(1L, null, 1, 20));
        assertEquals(40301, ex.getCode());
        verify(client, never()).recycleBin(any(), any(), anyInt(), anyInt());
    }


    @Test
    void 还原先查归属再判权乐观锁透传() {
        login(42L, "TEST_LEAD");
        projectClient.memberRoleResult(2);
        when(client.recycleEntry(7L)).thenReturn(recycleRow(1L));
        controller.restore(7L, 5);
        verify(client).restore(7L, 5);
    }

    @Test
    void 还原跨项目越权403不打restore() {
        login(42L, "TEST_LEAD");
        when(client.recycleEntry(7L)).thenReturn(recycleRow(2L));
        projectClient.crossProject(2L);
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.restore(7L, 5));
        assertEquals(40301, ex.getCode());
        verify(client, never()).restore(anyLong(), anyInt());
    }

    @Test
    void 还原系统级SKILL非ADMIN403() {
        login(42L, "TEST_LEAD");
        when(client.recycleEntry(7L)).thenReturn(recycleRow(null));
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.restore(7L, 5));
        assertEquals(40301, ex.getCode());
        verify(client, never()).restore(anyLong(), anyInt());
    }


    @Test
    void 回收站视图接收core十字段禁静默丢() throws Exception {
        String coreJson = """
                {"id":7,"projectId":1,"name":"回归技能","type":1,"status":1,"currentVersion":3,
                 "version":5,"deletedBy":42,"deletedByName":"测试人","deletedAt":"2026-09-19T10:00:00"}""";
        SkillRecycleView view = mapper.readValue(coreJson, SkillRecycleView.class);
        assertEquals(7L, view.id());
        assertEquals(1L, view.projectId());
        assertEquals("回归技能", view.name());
        assertEquals(1, view.type());
        assertEquals(1, view.status());
        assertEquals(3, view.currentVersion());
        assertEquals(5, view.version());
        assertEquals(42L, view.deletedBy());
        assertEquals("测试人", view.deletedByName());
        assertEquals(LocalDateTime.of(2026, 9, 19, 10, 0), view.deletedAt());
    }

    @Test
    void 存量无审计行删除人两列可空() throws Exception {
        String coreJson = """
                {"id":8,"projectId":null,"name":"旧技能","type":2,"status":3,"currentVersion":1,
                 "version":1,"deletedBy":null,"deletedByName":null,"deletedAt":null}""";
        SkillRecycleView view = mapper.readValue(coreJson, SkillRecycleView.class);
        assertNull(view.deletedBy());
        assertNull(view.deletedByName());
        assertNull(view.deletedAt());
        assertNull(view.projectId());
    }
}
