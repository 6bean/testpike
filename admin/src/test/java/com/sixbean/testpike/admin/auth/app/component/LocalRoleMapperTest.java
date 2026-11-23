package com.sixbean.testpike.admin.auth.app.component;

import com.sixbean.testpike.admin.auth.app.component.LocalRoleMapper;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;


import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class LocalRoleMapperTest {

    @Test
    void 四个预定义角色映射正确() {
        assertEquals(List.of("TESTER"), LocalRoleMapper.toRoleNames(1), "1=测试人员 → TESTER");
        assertEquals(List.of("TEST_LEAD"), LocalRoleMapper.toRoleNames(2), "2=测试负责人 → TEST_LEAD");
        assertEquals(List.of("DEVELOPER"), LocalRoleMapper.toRoleNames(3), "3=开发人员 → DEVELOPER");
        assertEquals(List.of("ADMIN"), LocalRoleMapper.toRoleNames(4), "4=系统管理员 → ADMIN");
    }

    @Test
    void 映射结果与ProjectAccessGuard角色常量一致() {
        assertTrue(LocalRoleMapper.toRoleNames(4).contains(ProjectAccessGuard.ROLE_ADMIN));
        assertTrue(LocalRoleMapper.toRoleNames(2).contains(ProjectAccessGuard.ROLE_TEST_LEAD));
    }

    @Test
    void 非法角色返回空列表_RBAC自定义角色兼容() {
        assertEquals(List.of(), LocalRoleMapper.toRoleNames(0));
        assertEquals(List.of(), LocalRoleMapper.toRoleNames(5));
    }
}
