package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseQuery;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.rbac.domain.service.RoleService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CaseDataScopeTest {

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture projects/users of this suite; the teardown sweeps only these rows. */
    private final java.util.List<Long> scopeTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.List<Long> scopeTrackedUsers =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        cleanTables();
        seedBuiltinRoles();
    }

    @AfterEach
    void restore() {
        cleanTables();
    }

    void cleanTables() {
        // Shared dev database (R37): tp_user and tp_role are factory-shared —
        // no blanket wipe anymore. The tracked-project FK sweep purges this
        // suite's cases, steps and audit rows; the tracked-user teardown is
        // the shared usersDown channel (role links and membership included).
        ProjectFixtureRig.sweep(jdbcTemplate, scopeTrackedProjects);
        scopeTrackedProjects.clear();
        SharedDbFixtureSweep.usersDown(jdbcTemplate, scopeTrackedUsers);
        scopeTrackedUsers.clear();
    }

    private void seedBuiltinRoles() {
        jdbcTemplate.execute("""
                INSERT INTO tp_role (name, code, description, data_scope, status, builtin) VALUES
                    ('测试人员', 'tester', '执行测试用例、提交缺陷', 3, 1, 1),
                    ('测试负责人', 'test_lead', '管理测试计划、分配用例、评审', 2, 1, 1),
                    ('开发人员', 'developer', '查看用例与缺陷（只读）', 2, 1, 1),
                    ('系统管理员', 'admin', '系统配置、用户与角色管理', 1, 1, 1)
                ON CONFLICT (code) DO NOTHING
                """);
    }

    private long createUser(String username, String roleCode) {
        // Exact-name leftover drop (crashed run), then track the fresh id for
        // the usersDown teardown. Equality match, never a LIKE prefix.
        SharedDbFixtureSweep.usersDown(jdbcTemplate, jdbcTemplate.queryForList(
                "SELECT id FROM tp_user WHERE username = ?", Long.class, username));
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, 'x', ?, 1, 1) RETURNING id
                """, Long.class, username, username);
        scopeTrackedUsers.add(id);
        Long roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_role WHERE code = ?", Long.class, roleCode);
        roleService.assignUserRoles(id, List.of(roleId));
        return id;
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                scopeTrackedProjects, "项目" + code, code);
    }

    private CaseCreateInput input(long projectId, String title, long createdBy) {
        return new CaseCreateInput(projectId, title, "前置", "预期", 1, 2, "web", 3, null, 1, null, List.of(new CaseStepInput(1, "操作", 0)));
    }

    private CaseQuery query(long projectId) {
        return new CaseQuery(projectId, null, null, null, null, null, null, null, null, null, 1, 50);
    }

    @Test
    void list_本人范围_只查本人创建或分配用例() {
        long pid = createProject("scope");
        long owner = createUser("owner_u", "tester");
        long other = createUser("other_u", "tester");

        long myCase = caseService.create(owner, input(pid, "我的用例", owner)).id();
        caseService.create(other, input(pid, "他人用例", other));
        long assignedToMe = caseService.create(other, input(pid, "分配给我", other)).id();
        jdbcTemplate.update("UPDATE tp_test_case SET assignee_id = ? WHERE id = ?", owner, assignedToMe);

        PageResult<CaseInfo> r = caseService.list(query(pid), owner);
        assertEquals(2, r.total(), "本人范围只含本人创建或分配给本人的用例");
        assertTrue(r.list().stream().allMatch(c ->
                        c.id() == myCase || c.id() == assignedToMe),
                "命中本人创建与分配给我的用例: " + r.list().stream().map(CaseInfo::title).toList());
    }

    @Test
    void list_项目内范围_本项目全量() {
        long pid = createProject("scope");
        long lead = createUser("lead_u", "test_lead");
        long other = createUser("other_u2", "tester");

        caseService.create(lead, input(pid, "负责人用例", lead));
        caseService.create(other, input(pid, "他人用例2", other));

        PageResult<CaseInfo> r = caseService.list(query(pid), lead);
        assertEquals(2, r.total(), "项目内范围=本项目全量");
    }

    @Test
    void list_全部范围_跨项目可见() {
        long pidA = createProject("scopeA");
        long pidB = createProject("scopeB");
        long admin = createUser("admin_u", "admin");

        caseService.create(admin, input(pidA, "A 项目用例", admin));
        caseService.create(admin, input(pidB, "B 项目用例", admin));

        PageResult<CaseInfo> r = caseService.list(query(0L), admin);
        // Shared dev db (R37): the global total also carries factory rows, so
        // the cross-project equality lives on the per-project counts while the
        // global scan keeps its lower bound (converted-family posture).
        assertTrue(r.total() >= 2, "全部范围跨项目可见 A+B 用例");
        assertEquals(1, caseService.list(query(pidA), admin).total(), "A 项目全量可见");
        assertEquals(1, caseService.list(query(pidB), admin).total(), "B 项目全量可见");
    }

    @Test
    void list_非全部范围跨项目_403人话() {
        long pid = createProject("scope");
        long tester = createUser("scope_t", "tester");
        caseService.create(tester, input(pid, "用例", tester));

        ServiceException e = assertThrows(ServiceException.class,
                () -> caseService.list(query(0L), tester));
        assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
        assertTrue(e.getMessage().contains("跨项目"), "人话提示: " + e.getMessage());
    }

    @Test
    void list_无操作人_服务间调用不应用本人过滤() {
        long pid = createProject("scope");
        long owner = createUser("svc_u", "tester");
        long other = createUser("svc_o", "tester");
        caseService.create(owner, input(pid, "用例1", owner));
        caseService.create(other, input(pid, "用例2", other));

        PageResult<CaseInfo> r = caseService.list(query(pid), null);
        assertEquals(2, r.total(), "服务间调用项目内全量");
    }
}
