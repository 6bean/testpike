package com.sixbean.testpike.core.common.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.api.dto.DeleteCheckResult;
import com.sixbean.testpike.core.common.api.dto.DeleteRefInfo;
import com.sixbean.testpike.core.plans.api.dto.CaseScopeRule;
import com.sixbean.testpike.core.plans.api.dto.PlanCreateInput;
import com.sixbean.testpike.core.plans.domain.service.PlanService;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.rbac.domain.service.RoleService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.tags.api.dto.TagCreateInput;
import com.sixbean.testpike.core.tags.api.dto.TagInfo;
import com.sixbean.testpike.core.tags.domain.service.TagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class DeleteGuardTest {

    @Autowired
    private DeleteGuard deleteGuard;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectEnvService projectEnvService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private PlanService planService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private TagService tagService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
 * R37 fixture-scoped teardown : the old blanket TRUNCATE
 * wiped factory-baseline projects, users and the shared sequence owners on
 * the dev database. The sweep deletes only rows reachable from this
 * suite's tracked fixture projects; the name-family purges below self-heal
 * crashed-run residue (fixed usernames and temp role codes hit unique
 * keys on the next round). tp_plan_case/tp_plan_deviation reference the
 * plan through a plain BIGINT with no FK, hence the join purge BEFORE the
 * project sweep. tp_role stays untouched except for this suite's temp_m4
 * family — the built-in 4 roles must survive (UserServiceTest ordering
 * postmortem).
 */
    private final java.util.List<Long> trackedProjects = new java.util.ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanFixtureRows() {
        SharedDbFixtureSweep.childRowsOfProjects(jdbcTemplate, "tp_plan_case",
                "plan_id", "tp_exec_plan", trackedProjects);
        SharedDbFixtureSweep.childRowsOfProjects(jdbcTemplate, "tp_plan_deviation",
                "plan_id", "tp_exec_plan", trackedProjects);
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, trackedProjects);
        trackedProjects.clear();
        jdbcTemplate.update("DELETE FROM tp_user_role WHERE role_id IN "
                + "(SELECT id FROM tp_role WHERE code LIKE 'temp_m4%') OR user_id IN "
                + "(SELECT id FROM tp_user WHERE username LIKE 'm4_%')");
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE user_id IN "
                + "(SELECT id FROM tp_user WHERE username LIKE 'm4_%')");
        jdbcTemplate.update("DELETE FROM tp_user WHERE username LIKE 'm4_%'");
        jdbcTemplate.update("DELETE FROM tp_role_func WHERE role_id IN "
                + "(SELECT id FROM tp_role WHERE code LIKE 'temp_m4%')");
        jdbcTemplate.update("DELETE FROM tp_role WHERE code LIKE 'temp_m4%'");
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                trackedProjects, "项目" + code, code);
    }

    private long createCase(long projectId, String title) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                1, 2, "web", 3, 1, 1, null, List.of()));
        return info.id();
    }

    private long createEnv(long projectId, String name, int envType) {
        Long eid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_project_env (project_id, name, env_type, base_url, version_snapshot)
                VALUES (?, ?, ?, ?, 'v1.0.0') RETURNING id
                """, Long.class, projectId, name, envType, "http://tp-local.test");
        return eid;
    }

    private long createBatch(long planId, Long envId) {
        Long bid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_batch (project_id, plan_id, env_id, batch_no, name, status, type, schedule_type, created_by)
                VALUES ((SELECT project_id FROM tp_exec_plan WHERE id = ?), ?, ?, 'B-M4', 'M4批次', 1, 1, 0, 1) RETURNING id
                """, Long.class, planId, planId, envId);
        return bid;
    }

    
    private long createPlan(long pid, long envId, CaseScopeRule scope) {
        return planService.create(1L, new PlanCreateInput(pid, "回归计划", "v1.0", 1L,
                List.of(envId), scope, null, null, null, null, null)).id();
    }

    private long createUser(String username) {
        Long uid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, username, username);
        return uid;
    }


    @Test
    void checkProject_含环境与用例_返回计数人话() {
        long pid = createProject("M4A");
        long env1 = createEnv(pid, "测试环境1", 2);
        createEnv(pid, "测试环境2", 3);
        createEnv(pid, "测试环境3", 4);
        createCase(pid, "登录用例");
        createCase(pid, "支付用例");
        createCase(pid, "订单用例");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE id = ?", env1);

        DeleteCheckResult r = deleteGuard.checkProject(pid);
        assertFalse(r.deletable(), "有引用不可直接删除");
        assertEquals(2, r.refs().size(), "环境 + 用例两类引用");
        DeleteRefInfo envRef = r.refs().get(0);
        assertEquals("env", envRef.type());
        assertEquals(2, envRef.count());
        assertTrue(envRef.message().contains("2 套环境"), envRef.message());
        DeleteRefInfo caseRef = r.refs().get(1);
        assertEquals("case", caseRef.type());
        assertEquals(3, caseRef.count());
        assertTrue(caseRef.message().contains("3 条用例"), caseRef.message());
        assertTrue(r.message().contains("2 套环境") && r.message().contains("3 条用例"),
                "人话汇总: " + r.message());
    }

    @Test
    void checkProject_含计划与成员_计数() {
        long pid = createProject("M4B");
        long envId = createEnv(pid, "测试环境1", 2);
        long planId = createPlan(pid, envId, CaseScopeRule.of(List.of(1), List.of(), null, List.of()));
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1)",
                pid, createUser("m4_member"));
        jdbcTemplate.update("UPDATE tp_exec_plan SET status = 5 WHERE id = ?", planId);

        DeleteCheckResult r = deleteGuard.checkProject(pid);
        assertFalse(r.deletable());
        assertTrue(r.refs().stream().anyMatch(ref -> ref.type().equals("plan") && ref.count() == 1),
                "计划引用计数");
        assertTrue(r.refs().stream().anyMatch(ref -> ref.type().equals("member") && ref.count() == 1),
                "成员引用计数");
    }

    @Test
    void checkProject_无引用_可删除() {
        long pid = createProject("M4C");
        DeleteCheckResult r = deleteGuard.checkProject(pid);
        assertTrue(r.deletable(), "空项目可删除");
        assertTrue(r.refs().isEmpty());
    }

    @Test
    void purge_项目被引用_409人话() {
        long pid = createProject("M4D");
        createEnv(pid, "测试环境1", 2);
        createEnv(pid, "测试环境2", 3);
        createCase(pid, "登录用例");
        createCase(pid, "支付用例");

        ServiceException e = assertThrows(ServiceException.class,
                () -> projectService.purge(1L, pid, false));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, e.getCode(), "被引用 → 40904");
        assertTrue(e.getMessage().contains("2 套环境"), e.getMessage());
        assertTrue(e.getMessage().contains("2 条用例"), e.getMessage());
    }

    @Test
    void purge_force_级联删除成功() {
        long pid = createProject("M4E");
        long envId = createEnv(pid, "测试环境1", 2);
        createCase(pid, "登录用例");
        createCase(pid, "支付用例");
        long planId = createPlan(pid, envId, CaseScopeRule.of(List.of(1), List.of(), null, List.of()));
        jdbcTemplate.update("UPDATE tp_exec_plan SET status = 5 WHERE id = ?", planId);
        createBatch(planId, envId);
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1)",
                pid, createUser("m4_member"));

        projectService.purge(1L, pid, true);

        assertTrue(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project WHERE id = ?", Long.class, pid) == 0, "项目已删");
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project_env WHERE id = ?", Long.class, envId) == 0, "环境已删");
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_test_case WHERE project_id = ?", Long.class, pid) == 0, "用例已删");
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_plan WHERE id = ?", Long.class, planId) == 0, "计划已删");
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_batch WHERE plan_id = ?", Long.class, planId) == 0, "批次已删");
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_user_project WHERE project_id = ?", Long.class, pid) == 0, "成员已删");
    }


    @Test
    void checkRole_被用户引用_返回计数() {
        String code = "temp_m4_" + System.nanoTime();
        long customId = roleService.create(new com.sixbean.testpike.core.rbac.api.dto.RoleSaveInput(
                "临时角色", code, null, 2, 1));
        long userId = createUser("m4_role_user");
        roleService.assignUserRoles(userId, List.of(customId));

        DeleteCheckResult r = deleteGuard.checkRole(customId);
        assertFalse(r.deletable());
        assertTrue(r.refs().stream().anyMatch(ref -> ref.type().equals("user") && ref.count() == 1));
        assertTrue(r.message().contains("1 名用户"), r.message());
    }

    @Test
    void roleDelete_被用户引用_409人话() {
        String code = "temp_m4b_" + System.nanoTime();
        long customId = roleService.create(new com.sixbean.testpike.core.rbac.api.dto.RoleSaveInput(
                "临时角色", code, null, 2, 1));
        long userId = createUser("m4_role_user2");
        roleService.assignUserRoles(userId, List.of(customId));

        ServiceException e = assertThrows(ServiceException.class, () -> roleService.delete(customId));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, e.getCode(), "被用户引用 → 40904");
        assertTrue(e.getMessage().contains("1 个用户"), e.getMessage());
    }

    @Test
    void checkPlan_被批次引用_返回计数() {
        long pid = createProject("M4F");
        long envId = createEnv(pid, "测试环境1", 2);
        long planId = createPlan(pid, envId, CaseScopeRule.of(List.of(1), List.of(), null, List.of()));
        jdbcTemplate.update("UPDATE tp_exec_plan SET status = 5 WHERE id = ?", planId);
        createBatch(planId, envId);

        DeleteCheckResult r = deleteGuard.checkPlan(planId);
        assertFalse(r.deletable());
        assertTrue(r.refs().stream().anyMatch(ref -> ref.type().equals("batch") && ref.count() == 1),
                "批次引用计数");
        assertTrue(r.message().contains("1 个执行批次"), r.message());
    }

    @Test
    void planDelete_被批次引用_409人话() {
        long pid = createProject("M4G");
        long envId = createEnv(pid, "测试环境1", 2);
        long planId = createPlan(pid, envId, CaseScopeRule.of(List.of(1), List.of(), null, List.of()));
        jdbcTemplate.update("UPDATE tp_exec_plan SET status = 5 WHERE id = ?", planId);
        createBatch(planId, envId);

        ServiceException e = assertThrows(ServiceException.class,
                () -> planService.delete(1L, planId, false));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, e.getCode(), "被批次引用 → 40904");
        assertTrue(e.getMessage().contains("执行批次"), e.getMessage());
    }

    @Test
    void planDelete_force_级联删批次成功() {
        long pid = createProject("M4H");
        long envId = createEnv(pid, "测试环境1", 2);
        long planId = createPlan(pid, envId, CaseScopeRule.of(List.of(1), List.of(), null, List.of()));
        jdbcTemplate.update("UPDATE tp_exec_plan SET status = 5 WHERE id = ?", planId);
        long batchId = createBatch(planId, envId);
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, batch_id, case_id, status)
                SELECT ?, ?, id, 1 FROM tp_test_case WHERE project_id = ? LIMIT 1
                """, pid, batchId, pid);

        planService.delete(1L, planId, true);

        assertTrue(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_plan WHERE id = ?", Long.class, planId) == 0, "计划已删");
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_batch WHERE id = ?", Long.class, batchId) == 0, "批次已删");
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE batch_id = ?", Long.class, batchId) == 0, "执行任务已删");
    }


    @Test
    void checkEnv_被批次引用_返回计数() {
        long pid = createProject("M4I");
        long envId = createEnv(pid, "测试环境1", 2);
        long planId = createPlan(pid, envId, CaseScopeRule.of(List.of(1), List.of(), null, List.of()));
        jdbcTemplate.update("UPDATE tp_exec_plan SET status = 5 WHERE id = ?", planId);
        createBatch(planId, envId);

        DeleteCheckResult r = deleteGuard.checkEnv(envId);
        assertFalse(r.deletable());
        assertTrue(r.refs().stream().anyMatch(ref -> ref.type().equals("batch") && ref.count() == 1));
    }

    @Test
    void envDelete_被批次引用_409人话() {
        long pid = createProject("M4J");
        long envId = createEnv(pid, "测试环境1", 2);
        long planId = createPlan(pid, envId, CaseScopeRule.of(List.of(1), List.of(), null, List.of()));
        jdbcTemplate.update("UPDATE tp_exec_plan SET status = 5 WHERE id = ?", planId);
        createBatch(planId, envId);

        ServiceException e = assertThrows(ServiceException.class,
                () -> projectEnvService.delete(pid, envId, false));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, e.getCode(), "被批次引用 → 40904");
        assertTrue(e.getMessage().contains("执行批次"), e.getMessage());
    }

    @Test
    void envDelete_force_批次env置空后删除成功() {
        long pid = createProject("M4K");
        long envId = createEnv(pid, "测试环境1", 2);
        long planId = createPlan(pid, envId, CaseScopeRule.of(List.of(1), List.of(), null, List.of()));
        jdbcTemplate.update("UPDATE tp_exec_plan SET status = 5 WHERE id = ?", planId);
        long batchId = createBatch(planId, envId);

        projectEnvService.delete(pid, envId, true);

        assertTrue(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project_env WHERE id = ?", Long.class, envId) == 0, "环境已删");
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT env_id FROM tp_exec_batch WHERE id = ?", Long.class, batchId) == null,
                "批次 env_id 已置空（数据保留）");
    }


    @Test
    void checkTag_被用例引用_返回计数() {
        long pid = createProject("M4L");
        long caseId = createCase(pid, "登录用例");
        TagInfo tag = tagService.create(1L, new TagCreateInput(pid, "回归集", 4));
        tagService.setCaseTags(1L, caseId, new com.sixbean.testpike.core.tags.api.dto.CaseTagSetInput(
                pid, List.of(tag.id())));

        DeleteCheckResult r = deleteGuard.checkTag(tag.id());
        assertFalse(r.deletable());
        assertTrue(r.refs().stream().anyMatch(ref -> ref.type().equals("case") && ref.count() == 1),
                "用例引用计数");
    }

    @Test
    void checkCase_被计划引用_返回计数() {
        long pid = createProject("M4M");
        long envId = createEnv(pid, "测试环境1", 2);
        long caseId = createCase(pid, "登录用例");
        long planId = createPlan(pid, envId,
                new CaseScopeRule(List.of(), List.of(), null, List.of(), List.of(caseId)));

        DeleteCheckResult r = deleteGuard.checkCase(caseId);
        assertFalse(r.deletable());
        assertTrue(r.refs().stream().anyMatch(ref -> ref.type().equals("plan") && ref.count() == 1),
                "计划引用计数: " + r.refs());
        assertTrue(r.message().contains("1 个执行计划"), r.message());
    }
}
