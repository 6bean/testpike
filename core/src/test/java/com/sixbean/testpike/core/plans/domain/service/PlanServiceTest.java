package com.sixbean.testpike.core.plans.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseUpdateInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.plans.api.dto.CaseScopeRule;
import com.sixbean.testpike.core.plans.api.dto.CompletionCheckView;
import com.sixbean.testpike.core.plans.api.dto.EntryCriteria;
import com.sixbean.testpike.core.plans.api.dto.ExitCriteria;
import com.sixbean.testpike.core.plans.api.dto.GenerateBatchResult;
import com.sixbean.testpike.core.plans.api.dto.PlanAssignInput;
import com.sixbean.testpike.core.plans.api.dto.PlanCaseInfo;
import com.sixbean.testpike.core.plans.api.dto.PlanCreateInput;
import com.sixbean.testpike.core.plans.api.dto.PlanDeviationInfo;
import com.sixbean.testpike.core.plans.api.dto.PlanInfo;
import com.sixbean.testpike.core.plans.api.dto.PlanProgressView;
import com.sixbean.testpike.core.plans.api.dto.PlanQuery;
import com.sixbean.testpike.core.plans.api.dto.PlanSummaryRow;
import com.sixbean.testpike.core.plans.api.dto.PlanUpdateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.MemberService;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.rbac.domain.service.RoleService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class PlanServiceTest {

    @Autowired
    private PlanService planService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectEnvService projectEnvService;
    @Autowired
    private MemberService memberService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private ScriptService scriptService;
    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Fixture projects created by this class, swept by id tree at each hook (R37②).
     * Testers are tracked by id: their usernames carry an underscore, which is a
     * LIKE single-char wildcard — pattern sweeps could hit other suites' rows, so
     * only exact-id/equality channels are used here.
     */
    private final List<Long> fixtureProjects = new ArrayList<>();
    private final List<Long> fixtureTesters = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Fixture-scoped teardown (R37②): the old blanket TRUNCATE also wiped
        // the shipped factory rows hanging off tp_project through its cascade.
        // tp_batch_report carries a project_id column but no FK into tp_project.
        SharedDbFixtureSweep.usersDown(jdbcTemplate, fixtureTesters);
        fixtureTesters.clear();
        SharedDbFixtureSweep.plansTreeDown(jdbcTemplate, List.of("tp_batch_report"),
                fixtureProjects);
        fixtureProjects.clear();
    }

    /** tp_batch_report carries a project_id column but no FK into tp_project. */
    private static final List<String> PLAN_EXTRA_TABLES = List.of("tp_batch_report");

    private long createProject(String code) {
        // Same-code leftover re-sweep + real service create + id tracking:
        // single source SharedDbFixtureSweep (R40/R37②). The same code repeats
        // across tests in this class, so this is also the in-run reset path.
        return SharedDbFixtureSweep.createPlanFixtureProject(projectService, jdbcTemplate,
                PLAN_EXTRA_TABLES, "项目", code, fixtureProjects);
    }

    private long createEnv(long pid, String name, int type) {
        return projectEnvService.create(pid, new ProjectEnvInput(name, type,
                "https://" + name + ".example.com", null, "web", "chromium", "v1.0.0"));
    }

    private long createCase(long pid, String title, int phase, int priority) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, title, "前置", "预期",
                priority, 2, "web", 3, phase, 1, null, List.of()));
        return info.id();
    }

    private void saveScript(long caseId) {
        scriptService.save(new ScriptSaveInput(caseId, "v2",
                "{\"caseId\": " + caseId + ", \"setup\": [], \"steps\": []}", 1));
    }

    private PlanCreateInput planInput(long pid, List<Long> envIds, CaseScopeRule scope) {
        return new PlanCreateInput(pid, "回归计划", "v1.2.0", null, envIds, scope,
                EntryCriteria.of(true), ExitCriteria.of(0.95, true), "2026-09-01", "2026-09-10", null);
    }

    private void updateCaseLastExec(long caseId, Integer result) {
        jdbcTemplate.update("UPDATE tp_test_case SET last_exec_result = ? WHERE id = ?", result, caseId);
    }


    @Test
    void test_plan_lifecycle_full() {
        long pid = createProject("L1A");
        long env1 = createEnv(pid, "测试环境A", 2);
        long env2 = createEnv(pid, "预发环境B", 3);
        long smokeCase = createCase(pid, "登录冒烟", 1, 0);
        long payCase = createCase(pid, "支付下单", 2, 1);
        long regressionCase = createCase(pid, "优惠券回归", 3, 1);
        long otherPid = createProject("L1X");
        createCase(otherPid, "他人用例", 3, 1);

        PlanInfo plan = planService.create(1L, planInput(pid, List.of(env1, env2),
                CaseScopeRule.of(List.of(1, 2, 3), List.of(0, 1), null, List.of())));
        assertEquals(1, plan.status(), "创建后为草稿");
        assertEquals(3, plan.caseCount(), "范围规则拉入 3 条用例");
        List<PlanCaseInfo> cases = planService.listCases(plan.id(), null);
        assertEquals(3, cases.size());
        assertTrue(cases.stream().noneMatch(c -> c.title().contains("他人用例")), "H1 跨项目隔离");

        long testerId = SharedDbFixtureSweep.createTrackedTester(jdbcTemplate, roleService, memberService, "tester_l1a", pid, fixtureTesters);
        long assigned = planService.assignCases(1L, plan.id(),
                new PlanAssignInput(cases.stream().map(PlanCaseInfo::caseId).toList(), testerId));
        assertEquals(3, assigned);
        cases = planService.listCases(plan.id(), null);
        assertTrue(cases.stream().allMatch(c -> testerId == c.assigneeId()), "全部已分配");

        PlanInfo published = planService.publish(1L, plan.id());
        assertEquals(2, published.status(), "发布后=已发布");

        updateCaseLastExec(smokeCase, 1);
        cases.forEach(c -> saveScript(c.caseId()));
        GenerateBatchResult batch = planService.generateBatches(1L, plan.id());
        assertEquals(2, batch.batchCount(), "按环境分 2 批");
        assertEquals(3, batch.taskCount(), "3 条用例均有脚本生成任务");
        assertEquals(0, batch.skippedNoScript());
        assertEquals(3, planService.get(plan.id()).status(), "生成批次后=执行中");

        List<PlanCaseInfo> after = planService.listCases(plan.id(), null);
        PlanCaseInfo first = after.get(0), second = after.get(1), third = after.get(2);
        assertNotNull(first.batchCaseRef(), "批次任务引用已回填");
        jdbcTemplate.update("UPDATE tp_plan_case SET status = ? WHERE id = ?", 3, first.id());
        jdbcTemplate.update("UPDATE tp_plan_case SET status = ? WHERE id = ?", 3, second.id());
        jdbcTemplate.update("UPDATE tp_plan_case SET status = ? WHERE id = ?", 4, third.id());

        PlanProgressView progress = planService.progress(plan.id());
        assertEquals(3, progress.total());
        assertEquals(2, progress.passed());
        assertEquals(1, progress.failed());
        assertEquals(0, progress.notRun());
        assertTrue(progress.byAssignee().stream()
                        .anyMatch(d -> d.label().contains("tester_l1a") && d.passed() == 2),
                "按人员维度聚合");
        assertTrue(progress.byPhase().stream().anyMatch(d -> d.label().contains("冒烟")));

        CompletionCheckView check = planService.completionCheck(1L, plan.id());
        assertFalse(check.passed(), "通过率低于阈值不可完成（TC-L1-02 准出不可完成）");
        assertEquals(3, planService.get(plan.id()).status(), "未达标保持执行中");

        jdbcTemplate.update("UPDATE tp_plan_case SET status = ? WHERE id = ?", 3, third.id());
        CompletionCheckView done = planService.completionCheck(1L, plan.id());
        assertTrue(done.passed(), "通过率 100% 达标");
        assertEquals(4, planService.get(plan.id()).status(), "准出达标自动完成（TC-L1-01）");
    }


    @Test
    void test_batch_task_backfill_chain() {
        long pid = createProject("L1F");
        long env1 = createEnv(pid, "测试环境F", 2);
        long case1 = createCase(pid, "回填用例A", 2, 1);
        long case2 = createCase(pid, "回填用例B", 2, 1);
        PlanInfo plan = planService.create(1L, planInput(pid, List.of(env1),
                CaseScopeRule.of(List.of(2), List.of(1), null, List.of())));
        long testerId = SharedDbFixtureSweep.createTrackedTester(jdbcTemplate, roleService, memberService, "tester_l1f", pid, fixtureTesters);
        planService.assignCases(1L, plan.id(),
                new PlanAssignInput(planService.listCases(plan.id(), null).stream()
                        .map(PlanCaseInfo::caseId).toList(), testerId));
        planService.publish(1L, plan.id());
        saveScript(case1);
        saveScript(case2);
        GenerateBatchResult batch = planService.generateBatches(1L, plan.id());
        assertEquals(2, batch.taskCount());

        List<Long> taskIds = jdbcTemplate.query(
                "SELECT t.id FROM tp_exec_task t JOIN tp_exec_batch b ON b.id = t.batch_id " +
                        "WHERE b.plan_id = ? ORDER BY t.id",
                (rs, rowNum) -> rs.getLong("id"), plan.id());
        assertEquals(2, taskIds.size(), "生成批次任务须关联批次（batch_id 非空）");

        for (Long taskId : taskIds) {
            jdbcTemplate.update("UPDATE tp_exec_task SET status = 2, claim_token = 'l4red' WHERE id = ?", taskId);
            execTaskService.submitResult(new ExecResultInput(taskId, "l4red", "pass",
                    null, null, null, null, null, null));
        }
        List<Integer> statuses = jdbcTemplate.query(
                "SELECT status FROM tp_plan_case WHERE plan_id = ? ORDER BY id",
                (rs, rowNum) -> rs.getInt("status"), plan.id());
        assertEquals(List.of(3, 3), statuses, "执行结果回传 → plan_case 状态回填为通过");
    }


    @Test
    void test_entry_smoke_block_generate() {
        long pid = createProject("L1B");
        long env1 = createEnv(pid, "测试环境B", 2);
        long smokeCase = createCase(pid, "登录冒烟", 1, 0);
        createCase(pid, "支付功能", 2, 1);
        updateCaseLastExec(smokeCase, 2);

        PlanInfo plan = planService.create(1L, planInput(pid, List.of(env1),
                CaseScopeRule.of(List.of(1, 2), List.of(0, 1), null, List.of())));
        long testerId = SharedDbFixtureSweep.createTrackedTester(jdbcTemplate, roleService, memberService, "tester_l1b", pid, fixtureTesters);
        planService.assignCases(1L, plan.id(),
                new PlanAssignInput(planService.listCases(plan.id(), null).stream()
                        .map(PlanCaseInfo::caseId).toList(), testerId));
        planService.publish(1L, plan.id());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> planService.generateBatches(1L, plan.id()));
        assertTrue(ex.getMessage().contains("冒烟"), "准入拦截人话：实际=" + ex.getMessage());
        assertEquals(2, planService.get(plan.id()).status(), "拦截后仍为已发布");

        updateCaseLastExec(smokeCase, 1);
        GenerateBatchResult batch = planService.generateBatches(1L, plan.id());
        assertEquals(1, batch.batchCount());
    }


    @Test
    void test_scope_invalid_400() {
        long pid = createProject("L1C");
        long env1 = createEnv(pid, "测试环境C", 2);

        ServiceException ex1 = assertThrows(ServiceException.class,
                () -> planService.create(1L, planInput(pid, List.of(),
                        CaseScopeRule.of(List.of(3), List.of(1), null, List.of()))));
        assertTrue(ex1.getMessage().contains("环境"), "环境空人话：" + ex1.getMessage());

        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> planService.create(1L, planInput(pid, List.of(env1),
                        CaseScopeRule.of(List.of(), List.of(), null, List.of()))));
        assertTrue(ex2.getMessage().contains("范围"), "范围空人话：" + ex2.getMessage());

        ServiceException ex3 = assertThrows(ServiceException.class,
                () -> planService.create(1L, planInput(pid, List.of(env1),
                        CaseScopeRule.of(List.of(9), List.of(1), null, List.of()))));
        assertTrue(ex3.getMessage().contains("阶段"), "阶段非法人话：" + ex3.getMessage());

        ServiceException ex4 = assertThrows(ServiceException.class,
                () -> planService.create(1L, planInput(pid, List.of(env1),
                        CaseScopeRule.of(List.of(3), List.of(5), null, List.of()))));
        assertTrue(ex4.getMessage().contains("优先级"), "优先级非法人话：" + ex4.getMessage());
    }

 // ── Server-side sorting through a physical-column whitelist (table-feature baseline) ──

    @Test
    void test_list_sorts_by_whitelisted_columns_both_directions() {
        long pid = createProject("PSRT");
        long env = createEnv(pid, "测试环境S", 2);
        // Insertion order deliberately differs from BOTH name orders, so the
        // id-DESC default cannot accidentally satisfy either direction assertion.
        planService.create(1L, withName(planInput(pid, List.of(env),
                CaseScopeRule.of(List.of(3), List.of(1), null, List.of())), "Z计划"));
        planService.create(1L, withName(planInput(pid, List.of(env),
                CaseScopeRule.of(List.of(3), List.of(1), null, List.of())), "A计划"));
        planService.create(1L, withName(planInput(pid, List.of(env),
                CaseScopeRule.of(List.of(3), List.of(1), null, List.of())), "M计划"));

        PageResult<PlanInfo> asc = planService.list(new PlanQuery(pid, null, null, 1, 20, "name", "asc"));
        assertEquals(java.util.List.of("A计划", "M计划", "Z计划"),
                asc.list().stream().map(PlanInfo::name).toList(), "asc must reorder server-side");

        PageResult<PlanInfo> desc = planService.list(new PlanQuery(pid, null, null, 1, 20, "name", "desc"));
        assertEquals(java.util.List.of("Z计划", "M计划", "A计划"),
                desc.list().stream().map(PlanInfo::name).toList(), "desc mirrors asc");

        // Default order (no sort requested) keeps the historical id-DESC behaviour.
        PageResult<PlanInfo> dflt = planService.list(new PlanQuery(pid, null, null, 1, 20));
        assertEquals(java.util.List.of("M计划", "A计划", "Z计划"),
                dflt.list().stream().map(PlanInfo::name).toList(), "default stays id DESC");
    }

    @Test
    void test_list_rejects_non_whitelisted_sort_key() {
        long pid = createProject("PSREJ");
        assertThrows(ServiceException.class,
                () -> planService.list(new PlanQuery(pid, null, null, 1, 20, "id; DROP TABLE x", "asc")),
                "injection-shaped sort key must fail closed");
    }

    /** Copy a plan input with a different name (records are immutable; sort assertions need distinct names). */
    private PlanCreateInput withName(PlanCreateInput in, String name) {
        return new PlanCreateInput(in.projectId(), name, in.targetVersion(), in.ownerId(), in.envIds(),
                in.caseScope(), in.entryCriteria(), in.exitCriteria(), in.planStart(), in.planEnd(), in.lockMode());
    }


    @Test
    void test_scope_explicit_case_ids_null_deserialization_no_npe() {
        long pid = createProject("L1F");
        long env1 = createEnv(pid, "测试环境F", 2);
        long caseId = createCase(pid, "回归用例", 3, 1);

        CaseScopeRule scope = new CaseScopeRule(List.of(3), List.of(1), null, List.of(), null);
        PlanInfo plan = planService.create(1L, planInput(pid, List.of(env1), scope));
        assertEquals(1, plan.caseCount(), "null explicitCaseIds 归一为空集合不 NPE，规则维度照常拉入");
        assertEquals(caseId, planService.listCases(plan.id(), null).get(0).caseId());
    }


    @Test
    void test_update_after_publish_blocked() {
        long pid = createProject("L1D");
        long env1 = createEnv(pid, "测试环境D", 2);
        createCase(pid, "回归用例", 3, 1);
        PlanInfo plan = planService.create(1L, planInput(pid, List.of(env1),
                CaseScopeRule.of(List.of(3), List.of(1), null, List.of())));
        long testerId = SharedDbFixtureSweep.createTrackedTester(jdbcTemplate, roleService, memberService, "tester_l1d", pid, fixtureTesters);
        planService.assignCases(1L, plan.id(),
                new PlanAssignInput(planService.listCases(plan.id(), null).stream()
                        .map(PlanCaseInfo::caseId).toList(), testerId));
        planService.publish(1L, plan.id());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> planService.update(1L, plan.id(), new PlanUpdateInput("改名", null, null,
                        List.of(env1), CaseScopeRule.of(List.of(2), List.of(0), null, List.of()),
                        EntryCriteria.of(true), ExitCriteria.of(0.95, true), null, null, null)));
        assertTrue(ex.getMessage().contains("锁定") || ex.getMessage().contains("发布"),
                "发布后改范围拦截人话：" + ex.getMessage());
    }


    @Test
    void test_l7_progress_dims_and_summary() {
        long pid = createProject("L7A");
        long env1 = createEnv(pid, "看板环境1", 2);
        long env2 = createEnv(pid, "看板环境2", 3);
        long c1 = createCase(pid, "看板冒烟", 1, 0);
        long c2 = createCase(pid, "看板功能", 2, 1);
        long c3 = createCase(pid, "看板回归A", 3, 1);
        long c4 = createCase(pid, "看板回归B", 3, 2);
        long testerId = SharedDbFixtureSweep.createTrackedTester(jdbcTemplate, roleService, memberService, "tester_l7a", pid, fixtureTesters);

        PlanInfo p1 = planService.create(1L, planInput(pid, List.of(env1, env2),
                CaseScopeRule.of(List.of(1, 2, 3), List.of(0, 1, 2), null, List.of())));
        assertEquals(4, p1.caseCount());
        planService.assignCases(1L, p1.id(), new PlanAssignInput(
                planService.listCases(p1.id(), null).stream().map(PlanCaseInfo::caseId).toList(), testerId));
        planService.publish(1L, p1.id());
        updateCaseLastExec(c1, 1);
        List.of(c1, c2, c3, c4).forEach(this::saveScript);
        assertEquals(2, planService.generateBatches(1L, p1.id()).batchCount(), "2 环境 2 批次");

        List<PlanCaseInfo> cases = planService.listCases(p1.id(), null);
        jdbcTemplate.update("UPDATE tp_plan_case SET status = ? WHERE case_id = ?", 3, c1);
        jdbcTemplate.update("UPDATE tp_plan_case SET status = ? WHERE case_id = ?", 4, c2);
        jdbcTemplate.update("UPDATE tp_plan_case SET status = ? WHERE case_id = ?", 2, c3);
        assertEquals(4, cases.size());

        PlanProgressView pv = planService.progress(p1.id());
        assertEquals(4, pv.total());
        assertEquals(1, pv.passed());
        assertEquals(1, pv.failed());
        assertEquals(1, pv.running());
        assertEquals(1, pv.notRun());
        assertEquals(0, pv.skipped());
        assertEquals(0.5, pv.passRate(), 1e-6, "通过率 = 通过/(通过+失败)");
        assertTrue(pv.byAssignee().stream().anyMatch(d -> d.label().contains("tester_l7a")
                        && d.passed() == 1 && d.failed() == 1 && d.running() == 1 && d.notRun() == 1),
                "按人员维度：执行人聚合正确，实际=" + pv.byAssignee());
        assertTrue(pv.byPhase().stream().anyMatch(d -> d.label().contains("冒烟") && d.passed() == 1),
                "按阶段维度：冒烟行通过 1，实际=" + pv.byPhase());
        assertTrue(pv.byPhase().stream().anyMatch(d -> d.label().contains("回归") && d.total() == 2),
                "按阶段维度：回归 2 条（running+notRun），实际=" + pv.byPhase());
        assertEquals(2, pv.byEnv().size(), "按环境维度：2 批次 2 行，实际=" + pv.byEnv());
        assertTrue(pv.byEnv().stream().anyMatch(d -> d.label().contains("看板环境1") && d.total() == 2),
                "环境 1 分到 2 条用例，实际=" + pv.byEnv());

        long c5 = createCase(pid, "看板草稿用例", 2, 2);
        PlanInfo p2 = planService.create(1L, planInput(pid, List.of(env1),
                CaseScopeRule.of(List.of(2), List.of(2), null, List.of())));
        assertEquals(1, p2.caseCount(), "P2 范围=功能阶段优先级2 → 仅 c5");
        assertEquals(c5, planService.listCases(p2.id(), null).get(0).caseId());

        List<PlanSummaryRow> rows = planService.progressSummary(pid);
        assertEquals(2, rows.size(), "项目 A 汇总 2 个计划，实际=" + rows);
        PlanSummaryRow r1 = rows.stream().filter(r -> r.planId() == p1.id()).findFirst().orElseThrow();
        assertEquals(4, r1.caseCount());
        assertEquals(1, r1.passed());
        assertEquals(1, r1.failed());
        assertEquals(1, r1.running());
        assertEquals(1, r1.notRun());
        assertEquals(0.5, r1.passRate(), 1e-6);
        assertEquals("执行中", r1.statusLabel());
        PlanSummaryRow r2 = rows.stream().filter(r -> r.planId() == p2.id()).findFirst().orElseThrow();
        assertEquals(1, r2.caseCount());
        assertEquals(1, r2.notRun());
        assertEquals(0.0, r2.passRate(), 1e-6, "无已执行 → 通过率 0");

        long pidB = createProject("L7B");
        long envB = createEnv(pidB, "看板环境B", 2);
        long cb = createCase(pidB, "看板B用例", 3, 1);
        PlanInfo p3 = planService.create(1L, planInput(pidB, List.of(envB),
                CaseScopeRule.of(List.of(3), List.of(1), null, List.of())));
        List<PlanSummaryRow> rowsB = planService.progressSummary(pidB);
        assertEquals(1, rowsB.size(), "项目 B 仅 1 个计划，实际=" + rowsB);
        assertEquals(p3.id(), rowsB.get(0).planId());
        assertTrue(planService.progressSummary(pid).stream().noneMatch(r -> r.planId() == p3.id()),
                "项目 A 汇总不含项目 B 计划（IT-L7-01）");
        assertEquals(cb, planService.listCases(p3.id(), null).get(0).caseId());
    }


    @Test
    void test_cancel_sync_batches() {
        long pid = createProject("L1E");
        long env1 = createEnv(pid, "测试环境E", 2);
        createCase(pid, "回归用例E", 3, 1);
        PlanInfo plan = planService.create(1L, planInput(pid, List.of(env1),
                CaseScopeRule.of(List.of(3), List.of(1), null, List.of())));
        long testerId = SharedDbFixtureSweep.createTrackedTester(jdbcTemplate, roleService, memberService, "tester_l1e", pid, fixtureTesters);
        planService.assignCases(1L, plan.id(),
                new PlanAssignInput(planService.listCases(plan.id(), null).stream()
                        .map(PlanCaseInfo::caseId).toList(), testerId));
        planService.publish(1L, plan.id());
        planService.generateBatches(1L, plan.id());
        Long batchId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_batch WHERE plan_id = ?", Long.class, plan.id());
        assertNotNull(batchId);

        planService.cancel(1L, plan.id());
        assertEquals(5, planService.get(plan.id()).status(), "计划已取消");
        Integer batchStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_exec_batch WHERE id = ?", Integer.class, batchId);
        assertEquals(8, batchStatus, "关联未执行批次同步取消（IT-L1-03）");
    }


    @Test
    void test_force_delete_cancelled_plan_with_batch_runtime_history() {
        long pid = createProject("L1F");
        long env1 = createEnv(pid, "测试环境F", 2);
        createCase(pid, "回归用例F", 3, 1);
        PlanInfo plan = planService.create(1L, planInput(pid, List.of(env1),
                CaseScopeRule.of(List.of(3), List.of(1), null, List.of())));
        long testerId = SharedDbFixtureSweep.createTrackedTester(jdbcTemplate, roleService, memberService, "tester_l1f", pid, fixtureTesters);
        planService.assignCases(1L, plan.id(),
                new PlanAssignInput(planService.listCases(plan.id(), null).stream()
                        .map(PlanCaseInfo::caseId).toList(), testerId));
        planService.publish(1L, plan.id());
        planService.generateBatches(1L, plan.id());
        Long batchId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_batch WHERE plan_id = ?", Long.class, plan.id());
        assertNotNull(batchId);
        jdbcTemplate.update("INSERT INTO tp_batch_param_snapshot (batch_id, gen, params_json) "
                + "VALUES (?, 1, '{}')", batchId);
        jdbcTemplate.update("INSERT INTO tp_batch_runtime_change (batch_id, kind) VALUES (?, 'start')", batchId);
        jdbcTemplate.update("INSERT INTO tp_batch_case (batch_id, case_id, case_version, result) "
                + "VALUES (?, ?, 1, 0)", batchId, planService.listCases(plan.id(), null).get(0).caseId());
        jdbcTemplate.update("INSERT INTO tp_batch_report (batch_id, project_id, total, batch_status) "
                + "VALUES (?, ?, 1, 5)", batchId, pid);

        planService.cancel(1L, plan.id());
        planService.delete(1L, plan.id(), true);

        // Shared dev database: other chains' plans live here too, so the
        // residue check slices by this fixture's project, never by the table.
        Integer planRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_plan WHERE project_id = ?", Integer.class, pid);
        assertEquals(0, planRows == null ? 0 : planRows, "tp_exec_plan 残留");
        Integer batchRows = jdbcTemplate.queryForObject("SELECT count(*) FROM tp_exec_batch WHERE plan_id = ?",
                Integer.class, plan.id());
        assertEquals(0, batchRows == null ? 0 : batchRows, "tp_exec_batch 残留");
        for (String table : List.of("tp_batch_param_snapshot", "tp_batch_runtime_change",
                "tp_batch_case", "tp_batch_report")) {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE batch_id = ?", Integer.class, batchId);
            assertEquals(0, n == null ? 0 : n, table + " 残留");
        }
    }


    @Test
    void test_m2_lock_mode_persist_and_publish_lock_409() {
        long pid = createProject("M2A");
        long env1 = createEnv(pid, "锁定环境", 2);
        createCase(pid, "锁定用例", 3, 1);
        PlanInfo plan = planService.create(1L, new PlanCreateInput(pid, "严格计划", "v2.0", null,
                List.of(env1), CaseScopeRule.of(List.of(3), List.of(1), null, List.of()),
                EntryCriteria.of(true), ExitCriteria.of(0.95, true), null, null, 2));
        assertEquals(2, plan.lockMode(), "lock_mode=2 严格持久化");
        long testerId = SharedDbFixtureSweep.createTrackedTester(jdbcTemplate, roleService, memberService, "tester_m2a", pid, fixtureTesters);
        planService.assignCases(1L, plan.id(),
                new PlanAssignInput(planService.listCases(plan.id(), null).stream()
                        .map(PlanCaseInfo::caseId).toList(), testerId));
        planService.publish(1L, plan.id());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> planService.update(1L, plan.id(), new PlanUpdateInput("改名", null, null,
                        List.of(env1), CaseScopeRule.of(List.of(2), List.of(0), null, List.of()),
                        EntryCriteria.of(true), ExitCriteria.of(0.95, true), null, null, null)));
        assertEquals(40904, ex.getCode(), "发布后修改应 40904（资源状态冲突）");
        assertTrue(ex.getMessage().contains("锁定"), "409 人话：" + ex.getMessage());
    }

    @Test
    void test_m2_strict_validate_case_blocks_then_allows_after_deviation() {
        long pid = createProject("M2B");
        long env1 = createEnv(pid, "严格环境", 2);
        long inPlan = createCase(pid, "计划内用例", 3, 1);
        long outPlan = createCase(pid, "计划外用例", 2, 2);
        PlanInfo strict = planService.create(1L, new PlanCreateInput(pid, "严格计划B", null, null,
                List.of(env1), CaseScopeRule.of(List.of(3), List.of(1), null, List.of()),
                EntryCriteria.of(true), ExitCriteria.of(0.95, true), null, null, 2));
        PlanInfo loose = planService.create(1L, planInput(pid, List.of(env1),
                CaseScopeRule.of(List.of(3), List.of(1), null, List.of())));

        planService.validateCaseAllowed(strict.id(), inPlan);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> planService.validateCaseAllowed(strict.id(), outPlan));
        assertEquals(40301, ex.getCode(), "严格模式计划外无偏差 → 40301");
        assertTrue(ex.getMessage().contains("偏差"), "403 人话：" + ex.getMessage());

        planService.registerDeviation(1L, strict.id(), outPlan, 1, "客户紧急补测，未纳入本期范围");
        planService.validateCaseAllowed(strict.id(), outPlan);

        planService.validateCaseAllowed(loose.id(), outPlan);
    }

    @Test
    void test_m2_deviation_list_and_cross_project() {
        long pid = createProject("M2C");
        long env1 = createEnv(pid, "偏差环境", 2);
        long c1 = createCase(pid, "偏差用例1", 3, 1);
        long c2 = createCase(pid, "偏差用例2", 2, 2);
        PlanInfo plan = planService.create(1L, new PlanCreateInput(pid, "偏差计划", null, null,
                List.of(env1), CaseScopeRule.of(List.of(3), List.of(1), null, List.of()),
                EntryCriteria.of(true), ExitCriteria.of(0.95, true), null, null, 2));
        planService.registerDeviation(1L, plan.id(), c1, 1, "计划外执行原因A");
        planService.registerDeviation(1L, plan.id(), c2, 2, "范围外变更原因B");

        List<PlanDeviationInfo> list = planService.listDeviations(plan.id());
        assertEquals(2, list.size(), "偏差列表 2 条");
        PlanDeviationInfo d1 = list.stream().filter(d -> d.caseId() == c1).findFirst().orElseThrow();
        PlanDeviationInfo d2 = list.stream().filter(d -> d.caseId() == c2).findFirst().orElseThrow();
        assertEquals(1, d1.deviationType());
        assertTrue(d1.reason().contains("原因A"));
        assertEquals(2, d2.deviationType());
        assertTrue(d2.reason().contains("原因B"));
        assertNotNull(d1.createdAt());
    }

    @Test
    void test_m2_case_reference_hint_and_strict_change_deviation() {
        long pid = createProject("M2D");
        long env1 = createEnv(pid, "引用环境", 2);
        long caseId = createCase(pid, "被引用用例", 3, 1);
        long testerId = SharedDbFixtureSweep.createTrackedTester(jdbcTemplate, roleService, memberService, "tester_m2d", pid, fixtureTesters);
        PlanInfo strict = planService.create(1L, new PlanCreateInput(pid, "严格计划D", "v1.2", null,
                List.of(env1), CaseScopeRule.of(List.of(3), List.of(1), null, List.of()),
                EntryCriteria.of(true), ExitCriteria.of(0.95, true), null, null, 2));
        planService.assignCases(1L, strict.id(), new PlanAssignInput(List.of(caseId), testerId));
        planService.publish(1L, strict.id());

        caseService.update(1L, caseId, new CaseUpdateInput("被引用用例改名", null, null,
                null, null, null, null, null), 1);
        List<String> refs = caseService.checkReferences(caseId);
        assertFalse(refs.isEmpty(), "被引用用例应返回引用提示");
        assertTrue(refs.get(0).contains("严格计划D"), "引用提示含计划名：" + refs);

        Long devCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_plan_deviation WHERE plan_id = ? AND case_id = ? AND deviation_type = 2",
                Long.class, strict.id(), caseId);
        assertEquals(1L, devCount, "严格模式核心字段变更应记录范围外变更偏差");
    }
}
