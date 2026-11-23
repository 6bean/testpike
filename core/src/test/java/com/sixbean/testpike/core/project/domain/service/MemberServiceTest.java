package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.project.api.dto.MemberInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.MemberService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;


import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;


@SpringBootTest(classes = CoreApplication.class)
class MemberServiceTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    private final java.util.List<Long> projectIds =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Creates a fixture project through the service path; sweeps a
     * crashed run's same-code leftover first, tracked for teardown. */
    private long createProject(String name, String code) {
        SharedDbFixtureSweep.byProject(jdbcTemplate,
                SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, code));
        long id = projectService.create(5L, new ProjectCreateInput(name, code, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    @BeforeEach
    @AfterEach
    void cleanTables() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, projectIds);
        // Suite-owned accounts carry fixed prefixes: targeted pattern purge.
        SharedDbFixtureSweep.usersByPatternDown(jdbcTemplate, "ut_member_%");
        SharedDbFixtureSweep.usersByPatternDown(jdbcTemplate, "ut_role_%");
    }

    private void initProject() {
        projectId = createProject("商品中台", "PSHOP");
    }

    @Test
    void add_新增成员_角色默认成员() {
        initProject();
        memberService.add(projectId, 9L, 1);

        List<MemberInfo> members = memberService.list(projectId);
        assertEquals(2, members.size(), "创建者 + 新成员");
        MemberInfo added = members.stream().filter(m -> m.userId() == 9L).findFirst().orElseThrow();
        assertEquals(1, added.role());
        assertEquals("成员", added.roleLabel());
    }

    @Test
    void list_成员带用户名昵称() {
        Long uid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, display_name, role, status)
                VALUES ('ut_member_' || floor(random() * 1000000)::int, '成员小测', 1, 1) RETURNING id
                """, Long.class);
        initProject();
        memberService.add(projectId, uid, 1);

        MemberInfo member = memberService.list(projectId).stream()
                .filter(m -> m.userId() != null && m.userId().longValue() == uid.longValue()).findFirst().orElseThrow();
        assertEquals(1, member.role());
        assertEquals("成员", member.roleLabel());
        org.junit.jupiter.api.Assertions.assertTrue(
                member.username() != null && member.username().startsWith("ut_member_"),
                "成员应带用户名: " + member.username());
        assertEquals("成员小测", member.nickName(), "成员应带昵称");
    }

    @Test
    void list_成员带RBAC角色编码() {
        Long uid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, display_name, role, status)
                VALUES ('ut_role_' || floor(random() * 1000000)::int, '角色小测', 1, 1) RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO tp_user_role (user_id, role_id)
                SELECT ?, id FROM tp_role WHERE code = ?
                """, uid, "tester");
        initProject();
        memberService.add(projectId, uid, 1);

        MemberInfo member = memberService.list(projectId).stream()
                .filter(m -> m.userId() != null && m.userId().longValue() == uid.longValue()).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(
                member.roles() != null && member.roles().contains("tester"),
                "成员应带 RBAC 角色编码: " + member.roles());
    }

    @Test
    void add_重复添加_更新角色不重复插入() {
        initProject();
        memberService.add(projectId, 9L, 1);
        memberService.add(projectId, 9L, 2);

        List<MemberInfo> members = memberService.list(projectId);
        assertEquals(2, members.size(), "重复添加不产生重复行（uq_user_project 唯一约束）");
        MemberInfo added = members.stream().filter(m -> m.userId() == 9L).findFirst().orElseThrow();
        assertEquals(2, added.role(), "角色被更新为负责人");
        assertEquals("项目负责人", added.roleLabel());
    }

    @Test
    void add_非法角色_参数校验() {
        initProject();
        ServiceException ex = assertThrows(ServiceException.class, () -> memberService.add(projectId, 9L, 9));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void updateRole_生效() {
        initProject();
        memberService.add(projectId, 9L, 1);
        memberService.updateRole(projectId, 9L, 2);

        assertEquals(2, memberService.list(projectId).stream()
                .filter(m -> m.userId() == 9L).findFirst().orElseThrow().role());
    }

    @Test
    void remove_物理删除成员关系() {
        initProject();
        memberService.add(projectId, 9L, 1);
        memberService.remove(projectId, 9L);

        assertEquals(1, memberService.list(projectId).size(), "成员关系已移除（仅剩创建者）");
        assertEquals(0, projectService.getMemberRole(projectId, 9L), "移除后不再是项目成员");
    }

    @Test
    void remove_名下用例回池_assignee置空() {
        initProject();
        memberService.add(projectId, 9L, 1);
        long caseId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_test_case (case_no, project_id, title, status, assignee_id, created_by)
                VALUES ('TC-PSHOP-001', ?, '待评审用例', 1, 9, 5) RETURNING id
                """, Long.class, projectId);

        memberService.remove(projectId, 9L);

        Integer assignee = jdbcTemplate.queryForObject(
                "SELECT assignee_id FROM tp_test_case WHERE id = ?", Integer.class, caseId);
        assertNull(assignee, "成员移出后名下用例回池（assignee_id 置空，状态不变）");
    }

    @Test
    void remove_唯一负责人_拒绝() {
        initProject();
        ServiceException ex = assertThrows(ServiceException.class, () -> memberService.remove(projectId, 5L));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), "项目至少保留一名项目负责人");
    }

    @Test
    void remove_项目不存在_抛404() {
        ServiceException ex = assertThrows(ServiceException.class, () -> memberService.remove(999999L, 9L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }
}
