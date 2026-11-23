package com.sixbean.testpike.core.plans.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.project.domain.service.MemberService;
import com.sixbean.testpike.core.plans.api.dto.CaseScopeRule;
import com.sixbean.testpike.core.plans.api.dto.EntryCriteria;
import com.sixbean.testpike.core.plans.api.dto.ExitCriteria;
import com.sixbean.testpike.core.plans.api.dto.GenerateBatchResult;
import com.sixbean.testpike.core.plans.api.dto.PlanAssignInput;
import com.sixbean.testpike.core.plans.api.dto.PlanCreateInput;
import com.sixbean.testpike.core.plans.api.dto.PlanInfo;
import com.sixbean.testpike.core.plans.api.dto.PlanCaseInfo;
import com.sixbean.testpike.core.plans.domain.service.impl.PlanServiceImpl;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.project.domain.service.MemberService;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.rbac.domain.service.RoleService;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class PlanBatchStartValidationTest {

    private static final String KEY_MODE = "testpike.batch.unevaluated-mode";

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
    @Autowired
    private ConfigCacheHolder configCacheHolder;

    /**
     * Fixture projects created by this class, swept by id tree at each hook (R37②).
     * Testers are tracked by id: their usernames carry an underscore, which is a
     * LIKE single-char wildcard — pattern sweeps could hit other suites' rows, so
     * only exact-id/equality channels are used here.
     */
    private final List<Long> fixtureProjects = new ArrayList<>();
    private final List<Long> fixtureTesters = new ArrayList<>();
    /** The N6 suite adds no non-FK project tables beyond the default sweep set. */
    private static final List<String> NO_EXTRA_TABLES = List.of();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Fixture-scoped teardown (R37②): the old blanket TRUNCATE also wiped
        // the shipped factory rows hanging off tp_project through its cascade.
        SharedDbFixtureSweep.usersDown(jdbcTemplate, fixtureTesters);
        fixtureTesters.clear();
        SharedDbFixtureSweep.plansTreeDown(jdbcTemplate, NO_EXTRA_TABLES, fixtureProjects);
        fixtureProjects.clear();
        configCacheHolder.remove(KEY_MODE);
    }

    @BeforeEach
    void seedConfig() {
        jdbcTemplate.update("""
                INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description, created_by)
                VALUES (?, 'skip', '批次', 'string', 0, '批次启动未通过评审用例处理：skip=跳过 / block=阻止', 1)
                ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value
                """, KEY_MODE);
        configCacheHolder.put(KEY_MODE, "skip");
    }

    private long createProject(String code) {
        // Same-code leftover re-sweep + real service create + id tracking:
        // single source SharedDbFixtureSweep (R40/R37②).
        return SharedDbFixtureSweep.createPlanFixtureProject(projectService, jdbcTemplate,
                NO_EXTRA_TABLES, "项目", code, fixtureProjects);
    }

    private long createEnv(long pid, String name, int type) {
        return projectEnvService.create(pid, new ProjectEnvInput(name, type,
                "https://" + name + ".example.com", null, "web", "chromium", "v1.0.0"));
    }

    
    private long createCase(long pid, String title, int status) {
        CaseCreateInput input = new CaseCreateInput(pid, title, "前置", "预期",
                1, 2, "web", status, 2, 1, null, List.of());
        return caseService.create(1L, input).id();
    }

    private void saveScript(long caseId) {
        scriptService.save(new ScriptSaveInput(caseId, "v2",
                "{\"caseId\": " + caseId + ", \"setup\": [], \"steps\": []}", 1));
    }

    private PlanCreateInput planInput(long pid, List<Long> envIds) {
        return new PlanCreateInput(pid, "批次校验计划", "v1.0.0", null, envIds,
                CaseScopeRule.of(List.of(2), List.of(0, 1), null, List.of()),
                EntryCriteria.of(true), ExitCriteria.of(0.95, true), "2026-09-01", "2026-09-10", null);
    }

    
    private record PlanFixture(long planId, long approvedCaseId, long unevaluatedCaseId, long testerId) {
    }

    private PlanFixture preparePlan(String tag) throws Exception {
        long pid = createProject("N6" + tag);
        long env1 = createEnv(pid, "测试环境A", 2);
        long approvedCase = createCase(pid, "已通过用例_" + tag, 3);
        long unevaluatedCase = createCase(pid, "未评审用例_" + tag, 1);
        PlanInfo plan = planService.create(1L, planInput(pid, List.of(env1)));
        long testerId = SharedDbFixtureSweep.createTrackedTester(jdbcTemplate, roleService, memberService, "tester_n6" + tag, pid, fixtureTesters);
        List<PlanCaseInfo> cases = planService.listCases(plan.id(), null);
        assertEquals(2, cases.size(), "范围拉入 2 条用例");
        planService.assignCases(1L, plan.id(), new PlanAssignInput(
                cases.stream().map(PlanCaseInfo::caseId).toList(), testerId));
        planService.publish(1L, plan.id());
        saveScript(approvedCase);
        saveScript(unevaluatedCase);
        return new PlanFixture(plan.id(), approvedCase, unevaluatedCase, testerId);
    }

    @Test
    void 未通过评审用例跳过_其余正常执行_skip清单落库() throws Exception {
        PlanFixture fx = preparePlan("A");

        GenerateBatchResult result = planService.generateBatches(1L, fx.planId());

        assertEquals(1, result.taskCount(), "仅已通过用例生成任务");
        assertEquals(1, result.skipCount(), "1 条未通过评审用例跳过");
        assertEquals(1, result.skippedCases().size());
        assertEquals(fx.unevaluatedCaseId, result.skippedCases().get(0).caseId());
        assertTrue(result.message().contains("跳过"), "message 人话含跳过提示: " + result.message());

        Long taskCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE batch_id IN (SELECT id FROM tp_exec_batch WHERE plan_id = ?)",
                Long.class, fx.planId());
        assertEquals(1, taskCount, "执行任务仅 1 条");
        Long unevalTask = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task t JOIN tp_exec_batch b ON b.id = t.batch_id" +
                        " WHERE b.plan_id = ? AND t.case_id = ?",
                Long.class, fx.planId(), fx.unevaluatedCaseId);
        assertEquals(0, unevalTask, "未评审用例不生成任务");

        String json = jdbcTemplate.queryForObject(
                "SELECT skip_cases_json FROM tp_exec_batch WHERE plan_id = ?", String.class, fx.planId());
        assertNotNull(json, "skip_cases_json 应落库");
        JsonNode arr = new ObjectMapper().readTree(json);
        assertEquals(1, arr.size());
        JsonNode item = arr.get(0);
        assertEquals(fx.unevaluatedCaseId, item.get("caseId").asLong());
        assertTrue(item.has("caseNo") && !item.get("caseNo").asText().isBlank(), "含 case_no");
        assertTrue(item.get("title").asText().contains("未评审用例"), "含标题: " + item);
        assertEquals(1, item.get("status").asInt(), "含当前状态（待评审=1）");
    }

    @Test
    void block模式_存在未通过用例_整体拒绝() throws Exception {
        configCacheHolder.put(KEY_MODE, "block");
        jdbcTemplate.update("UPDATE tp_sys_config SET config_value = 'block' WHERE config_key = ?", KEY_MODE);
        PlanFixture fx = preparePlan("B");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> planService.generateBatches(1L, fx.planId()));
        assertTrue(ex.getMessage().contains("未通过评审") || ex.getMessage().contains("未评审"),
                "block 人话应说明未通过评审用例: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("未评审用例_B"), "人话含用例标题: " + ex.getMessage());

        Long batchCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_batch WHERE plan_id = ?", Long.class, fx.planId());
        assertEquals(0, batchCount, "block 模式不生成批次");
    }

    @Test
    void 重复启动_已跳过用例不重复执行() throws Exception {
        PlanFixture fx = preparePlan("C");

        GenerateBatchResult first = planService.generateBatches(1L, fx.planId());
        String firstJson = jdbcTemplate.queryForObject(
                "SELECT skip_cases_json FROM tp_exec_batch WHERE plan_id = ?", String.class, fx.planId());

        GenerateBatchResult second = planService.generateBatches(1L, fx.planId());

        Long taskTotal = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task t JOIN tp_exec_batch b ON b.id = t.batch_id" +
                        " WHERE b.plan_id = ?", Long.class, fx.planId());
        assertEquals(1, taskTotal, "重复启动不重复生成已通过用例任务");
        Long unevalTask = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task t JOIN tp_exec_batch b ON b.id = t.batch_id" +
                        " WHERE b.plan_id = ? AND t.case_id = ?",
                Long.class, fx.planId(), fx.unevaluatedCaseId);
        assertEquals(0, unevalTask, "已跳过用例不因重复启动而执行（IT-N6-01）");
        assertEquals(first.skipCount(), second.skipCount(), "跳过清单一致（幂等）");
        String secondJson = jdbcTemplate.queryForObject(
                "SELECT skip_cases_json FROM tp_exec_batch WHERE plan_id = ? ORDER BY id DESC LIMIT 1",
                String.class, fx.planId());
        assertEquals(firstJson, secondJson, "skip_cases_json 幂等一致");
    }

    @Test
    void flaky隔离用例生成批次跳过_清单reason区分_不进任务() throws Exception {
        long pid = createProject("Q3");
        long env1 = createEnv(pid, "测试环境Q3", 2);
        long normalCase = createCase(pid, "正常用例_Q3", 3);
        long flakyCase = createCase(pid, "隔离用例_Q3", 3);
        caseService.setFlaky(1L, flakyCase, true, null);

        PlanInfo plan = planService.create(1L, planInput(pid, List.of(env1)));
        long testerId = SharedDbFixtureSweep.createTrackedTester(jdbcTemplate, roleService, memberService, "tester_q3", pid, fixtureTesters);
        List<PlanCaseInfo> cases = planService.listCases(plan.id(), null);
        planService.assignCases(1L, plan.id(), new PlanAssignInput(
                cases.stream().map(PlanCaseInfo::caseId).toList(), testerId));
        planService.publish(1L, plan.id());
        saveScript(normalCase);
        saveScript(flakyCase);

        GenerateBatchResult result = planService.generateBatches(1L, plan.id());
        assertEquals(1, result.skipCount(), "仅 flaky 用例跳过");
        assertEquals(flakyCase, result.skippedCases().get(0).caseId());
        assertEquals("已隔离（不稳定）", result.skippedCases().get(0).reason(), "reason 区分 Q3/N6");
        assertTrue(result.message().contains("不稳定隔离"), "message 人话: " + result.message());
        Long flakyTask = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE case_id = ?", Long.class, flakyCase);
        assertEquals(0, flakyTask, "隔离用例不生成执行任务");

        jdbcTemplate.update("UPDATE tp_sys_config SET config_value = 'block' WHERE config_key = ?", KEY_MODE);
        configCacheHolder.put(KEY_MODE, "block");
        long pid2 = createProject("Q3B");
        long env2 = createEnv(pid2, "测试环境Q3B", 2);
        long flakyCase2 = createCase(pid2, "隔离用例_Q3B", 3);
        caseService.setFlaky(1L, flakyCase2, true, null);
        PlanInfo plan2 = planService.create(1L, planInput(pid2, List.of(env2)));
        long testerId2 = SharedDbFixtureSweep.createTrackedTester(jdbcTemplate, roleService, memberService, "tester_q3b", pid2, fixtureTesters);
        List<PlanCaseInfo> cases2 = planService.listCases(plan2.id(), null);
        planService.assignCases(1L, plan2.id(), new PlanAssignInput(
                cases2.stream().map(PlanCaseInfo::caseId).toList(), testerId2));
        planService.publish(1L, plan2.id());
        saveScript(flakyCase2);
        GenerateBatchResult blockResult = planService.generateBatches(1L, plan2.id());
        assertEquals(1, blockResult.skipCount(), "block 模式下 flaky 仍按跳过处理（不阻止）");
        assertEquals("已隔离（不稳定）", blockResult.skippedCases().get(0).reason());
    }
}
