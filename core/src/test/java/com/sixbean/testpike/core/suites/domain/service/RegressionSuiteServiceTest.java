package com.sixbean.testpike.core.suites.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.plans.api.dto.PlanInfo;
import com.sixbean.testpike.core.plans.domain.service.PlanService;
import com.sixbean.testpike.core.project.domain.service.MemberService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.rbac.domain.service.RoleService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.suites.api.dto.SuiteCreateInput;
import com.sixbean.testpike.core.suites.api.dto.SuiteInfo;
import com.sixbean.testpike.core.suites.api.dto.SuiteQuery;
import com.sixbean.testpike.core.suites.api.dto.SuiteRunResult;
import com.sixbean.testpike.core.suites.api.dto.SuiteUpdateInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class RegressionSuiteServiceTest {

    @Autowired
    private RegressionSuiteService suiteService;

    @Autowired
    private PlanService planService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private ScriptService scriptService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Fixture projects/testers created by this class, swept by id tree at each
     * hook (R37②). Usernames here carry no underscore, but the teardown still
     * goes through the exact-id channel of the shared helper so a crashed run's
     * leftovers are cleared deterministically.
     */
    private final List<Long> fixtureProjects = new ArrayList<>();
    private final List<Long> fixtureTesters = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Fixture-scoped teardown (R37②) through the suites-family single
        // source; the old blanket TRUNCATE also wiped the shared fleet
        // registry tp_executor and every concurrent suite's dev rows.
        SharedDbFixtureSweep.suitesTreeDown(jdbcTemplate, fixtureTesters, fixtureProjects);
    }

    private long createProject(String code) {
        return SharedDbFixtureSweep.createSuiteFixtureProject(projectService, jdbcTemplate,
                code, fixtureProjects);
    }

    private long createCase(long projectId, String title, Integer phase) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                1, 2, "web", 3, phase, 1, null, List.of()));
        return info.id();
    }

    
    private long createTester(String username, long pid) {
        // Single-source tester create (R40): exact-username leftover pre-delete,
        // real role/member services, id tracked for the per-hook teardown.
        return SharedDbFixtureSweep.createTrackedTester(jdbcTemplate, roleService,
                memberService, username, pid, fixtureTesters);
    }

    private long createEnv(long projectId, String name, int envType) {
        Long eid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_project_env (project_id, name, env_type, base_url, version_snapshot)
                VALUES (?, ?, ?, ?, 'v1.0.0') RETURNING id
                """, Long.class, projectId, name, envType, "http://tp-local.test");
        return eid;
    }

    private void saveScript(long caseId) {
        scriptService.save(new ScriptSaveInput(caseId, "v2",
                "{\"caseId\": " + caseId + ", \"setup\": [], \"steps\": []}", 1));
    }

    private SuiteCreateInput suiteInput(long pid, List<Long> caseIds, List<Long> envIds,
                                        List<Long> assigneeIds, String name) {
        return new SuiteCreateInput(pid, name, caseIds, envIds, assigneeIds,
                "0 0 2 * * MON", 1);
    }

    @Test
    void test_crud_and_list_filters() {
        long pid = createProject("L3A");
        long pidB = createProject("L3AB");
        long c1 = createCase(pid, "登录回归", 3);
        long c2 = createCase(pid, "支付回归", 3);
        long env1 = createEnv(pid, "测试环境", 2);

        SuiteInfo created = suiteService.create(1L,
                suiteInput(pid, List.of(c1, c2), List.of(env1), List.of(), "发版前核心回归"));
        assertNotNull(created.id());
        assertEquals(2, created.caseCount(), "用例数");
        assertEquals(1, created.status());

        SuiteInfo got = suiteService.get(created.id());
        assertEquals("发版前核心回归", got.name());
        assertEquals(2, got.caseIds().size());

        PageResult<SuiteInfo> kw = suiteService.list(new SuiteQuery(pid, "回归", null, 1, 10));
        assertEquals(1, kw.total());
        PageResult<SuiteInfo> none = suiteService.list(new SuiteQuery(pid, "不存在xyz", null, 1, 10));
        assertEquals(0, none.total());
        PageResult<SuiteInfo> other = suiteService.list(new SuiteQuery(pidB, null, null, 1, 10));
        assertEquals(0, other.total(), "H1 项目隔离");

        SuiteInfo updated = suiteService.update(1L, created.id(),
                new SuiteUpdateInput("核心回归（改名）", List.of(c1), List.of(env1), List.of(), null, 2));
        assertEquals("核心回归（改名）", updated.name());
        assertEquals(1, updated.caseIds().size());
        assertEquals(2, updated.status());
        PageResult<SuiteInfo> disabled = suiteService.list(new SuiteQuery(pid, null, 2, 1, 10));
        assertEquals(1, disabled.total());

        suiteService.delete(1L, created.id());
        ServiceException gone = assertThrows(ServiceException.class, () -> suiteService.get(created.id()));
        assertTrue(gone.getMessage().contains("回归集不存在"), gone.getMessage());
    }

    // ── Server-side sorting through a physical-column whitelist (table-feature baseline) ──

    @Test
    void test_list_sorts_by_whitelisted_columns_both_directions() {
        long pid = createProject("SSRT");
        long env = createEnv(pid, "测试环境X", 2);
        long c1 = createCase(pid, "排序用例一", 3);
        long c2 = createCase(pid, "排序用例二", 3);
        // Insertion order deliberately differs from BOTH name orders, so the
        // id-DESC default cannot accidentally satisfy either direction assertion.
        suiteService.create(1L, suiteInput(pid, List.of(c1, c2), List.of(env), List.of(), "Z回归集"));
        suiteService.create(1L, suiteInput(pid, List.of(c1), List.of(env), List.of(), "A回归集"));
        suiteService.create(1L, suiteInput(pid, List.of(c1, c2), List.of(env), List.of(), "M回归集"));

        PageResult<SuiteInfo> asc = suiteService.list(new SuiteQuery(pid, null, null, 1, 20, "name", "asc"));
        assertEquals(List.of("A回归集", "M回归集", "Z回归集"),
                asc.list().stream().map(SuiteInfo::name).toList(), "asc must reorder server-side");

        PageResult<SuiteInfo> desc = suiteService.list(new SuiteQuery(pid, null, null, 1, 20, "name", "desc"));
        assertEquals(List.of("Z回归集", "M回归集", "A回归集"),
                desc.list().stream().map(SuiteInfo::name).toList(), "desc mirrors asc");

        // Default order (no sort requested) keeps the historical id-DESC behaviour.
        PageResult<SuiteInfo> dflt = suiteService.list(new SuiteQuery(pid, null, null, 1, 20));
        assertEquals(List.of("M回归集", "A回归集", "Z回归集"),
                dflt.list().stream().map(SuiteInfo::name).toList(), "default stays id DESC");

        // case_count is a select subquery alias (not a physical column) — sorting by it
        // must order 1 < 2 (A=1 case, Z=2 cases, M=2 cases with id-DESC tie-break).
        PageResult<SuiteInfo> byCount = suiteService.list(new SuiteQuery(pid, null, null, 1, 20, "case_count", "asc"));
        assertEquals(List.of("A回归集", "M回归集", "Z回归集"),
                byCount.list().stream().map(SuiteInfo::name).toList(), "case_count alias sorts asc");
    }

    @Test
    void test_list_rejects_non_whitelisted_sort_key() {
        long pid = createProject("SSREJ");
        assertThrows(ServiceException.class,
                () -> suiteService.list(new SuiteQuery(pid, null, null, 1, 20, "id OR 1=1", "asc")),
                "injection-shaped sort key must fail closed");
    }

    @Test
    void test_run_one_click_generates_plan_and_batches() {
        long pid = createProject("L3B");
        long env1 = createEnv(pid, "测试环境A", 2);
        long env2 = createEnv(pid, "预发环境B", 3);
        long c1 = createCase(pid, "支付回归-1", 3);
        long c2 = createCase(pid, "支付回归-2", 3);
        long c3 = createCase(pid, "支付回归-3", 3);
        saveScript(c1);
        saveScript(c2);
        saveScript(c3);
        long t1 = createTester("l3t1", pid);
        long t2 = createTester("l3t2", pid);

        SuiteInfo suite = suiteService.create(1L,
                suiteInput(pid, List.of(c1, c2, c3), List.of(env1, env2), List.of(t1, t2), "发版回归"));

        SuiteRunResult result = suiteService.run(1L, suite.id());
        assertTrue(result.planId() > 0);
        assertEquals(2, result.batchCount(), "2 环境 → 2 批次");
        assertEquals(3, result.taskCount(), "3 用例有脚本 → 3 任务");
        assertEquals(0, result.skippedArchived());
        assertTrue(result.message().contains("2 个环境"), result.message());

        PlanInfo plan = planService.get(result.planId());
        assertEquals(3, plan.status());
        assertTrue(plan.name().contains("发版回归"), plan.name());
        assertEquals(3, plan.caseCount());

        Long planCases = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_plan_case WHERE plan_id = ?", Long.class, result.planId());
        assertEquals(3, planCases);
        Long unassigned = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_plan_case WHERE plan_id = ? AND assignee_id IS NULL",
                Long.class, result.planId());
        assertEquals(0, unassigned, "默认执行人已全部分配");
        Long batches = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_batch WHERE plan_id = ?", Long.class, result.planId());
        assertEquals(2, batches, "批次挂计划");

        SuiteInfo after = suiteService.get(suite.id());
        assertNotNull(after.lastRunAt(), "一键生成后 last_run_at 落库");
    }

    @Test
    void test_run_skips_archived_and_deleted_cases() {
        long pid = createProject("L3C");
        long env1 = createEnv(pid, "测试环境", 2);
        long valid = createCase(pid, "有效回归用例", 3);
        long archived = createCase(pid, "已归档用例", 3);
        long deleted = createCase(pid, "已删除用例", 3);
        jdbcTemplate.update("UPDATE tp_test_case SET status = 9 WHERE id = ?", archived);
        jdbcTemplate.update("UPDATE tp_test_case SET deleted = 1 WHERE id = ?", deleted);
        saveScript(valid);
        long t1 = createTester("l3c1", pid);

        SuiteInfo suite = suiteService.create(1L,
                suiteInput(pid, List.of(valid, archived, deleted), List.of(env1), List.of(t1), "归档跳过回归"));

        SuiteRunResult result = suiteService.run(1L, suite.id());
        assertEquals(2, result.skippedArchived(), "归档 + 软删各 1 条自动跳过");
        assertTrue(result.message().contains("跳过"), result.message());
        assertEquals(1, result.taskCount());

        Long planCases = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_plan_case WHERE plan_id = ?", Long.class, result.planId());
        assertEquals(1, planCases, "计划仅含有效用例");
    }

    @Test
    void test_run_validation_stopped_no_assignee_and_all_archived() {
        long pid = createProject("L3D");
        long env1 = createEnv(pid, "测试环境", 2);
        long c1 = createCase(pid, "待跑用例", 3);
        long t1 = createTester("l3d1", pid);

        SuiteInfo stopped = suiteService.create(1L,
                suiteInput(pid, List.of(c1), List.of(env1), List.of(t1), "停用集"));
        suiteService.update(1L, stopped.id(),
                new SuiteUpdateInput(stopped.name(), List.of(c1), List.of(env1), List.of(t1), null, 2));
        ServiceException stoppedEx = assertThrows(ServiceException.class,
                () -> suiteService.run(1L, stopped.id()));
        assertTrue(stoppedEx.getMessage().contains("已停用"), stoppedEx.getMessage());

        SuiteInfo noAssignee = suiteService.create(1L,
                suiteInput(pid, List.of(c1), List.of(env1), List.of(), "无执行人集"));
        ServiceException assigneeEx = assertThrows(ServiceException.class,
                () -> suiteService.run(1L, noAssignee.id()));
        assertTrue(assigneeEx.getMessage().contains("默认执行人"), assigneeEx.getMessage());

        long archived = createCase(pid, "全归档用例", 3);
        jdbcTemplate.update("UPDATE tp_test_case SET status = 9 WHERE id = ?", archived);
        SuiteInfo allArchived = suiteService.create(1L,
                suiteInput(pid, List.of(archived), List.of(env1), List.of(t1), "全归档集"));
        ServiceException archEx = assertThrows(ServiceException.class,
                () -> suiteService.run(1L, allArchived.id()));
        assertTrue(archEx.getMessage().contains("归档"), archEx.getMessage());
    }

    @Test
    void test_assignee_and_cross_project_validation() {
        long pid = createProject("L3E");
        long pidB = createProject("L3EB");
        long c1 = createCase(pid, "用例甲", 3);
        long env1 = createEnv(pid, "测试环境", 2);
        long outsider = createTester("l3out", pidB);

        ServiceException member = assertThrows(ServiceException.class, () -> suiteService.create(1L,
                suiteInput(pid, List.of(c1), List.of(env1), List.of(outsider), "跨项目执行人")));
        assertTrue(member.getMessage().contains("不是本项目成员"), member.getMessage());

        long caseB = createCase(pidB, "B 项目用例", 3);
        ServiceException cross = assertThrows(ServiceException.class, () -> suiteService.create(1L,
                suiteInput(pid, List.of(c1, caseB), List.of(env1), List.of(), "跨项目用例")));
        assertTrue(cross.getMessage().contains("不属于该项目"), cross.getMessage());
    }
}
