package com.sixbean.testpike.core.project.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.MemberService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <ul>
 * </ul>
 *
 */
@SpringBootTest(classes = CoreApplication.class)
class ProjectRoleResolverTest {

    @Autowired
    private ProjectRoleResolver roleResolver;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> projectIds =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, projectIds);
    }

    private long createProject(String code) {
        SharedDbFixtureSweep.byProject(jdbcTemplate,
                SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, code));
        long id = projectService.create(5L,
                new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }


    @Test
    void roleOf_非成员返回0() {
        long pid = createProject("H2A");
        assertEquals(ProjectRoleResolver.ROLE_NONE, roleResolver.roleOf(pid, 999L),
                "无 tp_user_project 行=非成员 0（IT-RBAC-01 基线态）");
    }

    @Test
    void roleOf_普通成员返回1() {
        long pid = createProject("H2B");
        memberService.add(pid, 9L, ProjectRoleResolver.ROLE_MEMBER);
        assertEquals(ProjectRoleResolver.ROLE_MEMBER, roleResolver.roleOf(pid, 9L), "成员=1");
    }

    @Test
    void roleOf_creator隐式负责人为2() {
        long pid = createProject("H2C");
        assertEquals(ProjectRoleResolver.ROLE_OWNER, roleResolver.roleOf(pid, 5L),
                "创建人=隐式负责人（存量零变化）");
        assertTrue(roleResolver.isOwner(pid, 5L), "creator 便捷面 isOwner=true");
    }


    @Test
    void isOwnerRole_阈值为ge2() {
        assertFalse(ProjectRoleResolver.isOwnerRole(0), "非成员非负责人");
        assertFalse(ProjectRoleResolver.isOwnerRole(1), "普通成员非负责人");
        assertTrue(ProjectRoleResolver.isOwnerRole(2), "role=2 负责人（判定单一来源）");
    }

    @Test
    void isMemberRole_阈值为ge1() {
        assertFalse(ProjectRoleResolver.isMemberRole(0), "非成员");
        assertTrue(ProjectRoleResolver.isMemberRole(1), "成员");
        assertTrue(ProjectRoleResolver.isMemberRole(2), "负责人同时是成员（≥1 语义）");
    }


    @Test
    void 设显式负责人后即时生效() {
        long pid = createProject("H2D");
        assertFalse(roleResolver.isOwner(pid, 9L), "升级前非负责人");
        memberService.add(pid, 9L, ProjectRoleResolver.ROLE_OWNER);
        assertTrue(roleResolver.isOwner(pid, 9L), "升级后下一次判定即生效（无缓存层）");
    }

    @Test
    void 移除成员后即时失效() {
        long pid = createProject("H2E");
        memberService.add(pid, 9L, ProjectRoleResolver.ROLE_OWNER);
        memberService.add(pid, 8L, ProjectRoleResolver.ROLE_OWNER);
        memberService.remove(pid, 9L);
        assertEquals(ProjectRoleResolver.ROLE_NONE, roleResolver.roleOf(pid, 9L),
                "移除后即时失效=非成员（IT-RBAC-01 显式负责人删除即时生效）");
    }

    @Test
    void 移交负责人原负责人降为成员() {
        long pid = createProject("H2F");
        memberService.add(pid, 9L, ProjectRoleResolver.ROLE_OWNER);
        memberService.add(pid, 5L, ProjectRoleResolver.ROLE_MEMBER);
        assertFalse(roleResolver.isOwner(pid, 5L), "移交后原负责人降成员，负责人判定失效（TC-H2-02 后半）");
        assertTrue(roleResolver.isMember(pid, 5L), "降为成员仍具成员语义");
        assertTrue(roleResolver.isOwner(pid, 9L), "新负责人生效");
    }

    @Test
    void 多负责人并存各自判定为2() {
        long pid = createProject("H2G");
        memberService.add(pid, 9L, ProjectRoleResolver.ROLE_OWNER);
        assertTrue(roleResolver.isOwner(pid, 5L), "原负责人保留");
        assertTrue(roleResolver.isOwner(pid, 9L), "新负责人并存（多负责人天然支持，remove 防呆有 ≥2 计数）");
    }

    @Test
    void isMember_非成员与成员两态() {
        long pid = createProject("H2H");
        assertFalse(roleResolver.isMember(pid, 77L), "无行=非成员");
        memberService.add(pid, 77L, ProjectRoleResolver.ROLE_MEMBER);
        assertTrue(roleResolver.isMember(pid, 77L), "add 后即时=成员");
    }
}
