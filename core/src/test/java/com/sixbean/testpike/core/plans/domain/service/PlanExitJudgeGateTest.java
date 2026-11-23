package com.sixbean.testpike.core.plans.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.judge.api.dto.EvalSetVersionView;
import com.sixbean.testpike.core.judge.api.dto.JudgeReviewInput;
import com.sixbean.testpike.core.judge.api.dto.RecordInput;
import com.sixbean.testpike.core.judge.api.dto.RubricDimension;
import com.sixbean.testpike.core.judge.api.dto.RubricSaveInput;
import com.sixbean.testpike.core.judge.api.dto.RubricView;
import com.sixbean.testpike.core.judge.api.dto.RunCreateInput;
import com.sixbean.testpike.core.judge.api.dto.RunView;
import com.sixbean.testpike.core.judge.api.dto.SampleInput;
import com.sixbean.testpike.core.judge.api.dto.SampleView;
import com.sixbean.testpike.core.judge.domain.service.JudgeRecordService;
import com.sixbean.testpike.core.judge.domain.service.JudgeReviewService;
import com.sixbean.testpike.core.judge.domain.service.JudgeRubricService;
import com.sixbean.testpike.core.judge.domain.service.JudgeRunService;
import com.sixbean.testpike.core.judge.domain.service.JudgeSampleService;
import com.sixbean.testpike.core.plans.api.dto.CaseScopeRule;
import com.sixbean.testpike.core.plans.api.dto.CompletionCheckView;
import com.sixbean.testpike.core.plans.api.dto.EntryCriteria;
import com.sixbean.testpike.core.plans.api.dto.ExitCriteria;
import com.sixbean.testpike.core.plans.api.dto.PlanAssignInput;
import com.sixbean.testpike.core.plans.api.dto.PlanCaseInfo;
import com.sixbean.testpike.core.plans.api.dto.PlanCreateInput;
import com.sixbean.testpike.core.plans.api.dto.PlanInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.MemberService;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.rbac.api.dto.RoleInfo;
import com.sixbean.testpike.core.rbac.domain.service.RoleService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class PlanExitJudgeGateTest {

    private static final String BOOTSTRAP_KEY = "judge.calibration.bootstrap-samples";

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
    private JudgeRubricService judgeRubricService;
    @Autowired
    private JudgeSampleService judgeSampleService;
    @Autowired
    private JudgeRunService judgeRunService;
    @Autowired
    private JudgeRecordService judgeRecordService;
    @Autowired
    private JudgeReviewService judgeReviewService;
    @Autowired
    private ConfigService configService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture projects created by this class, swept by id tree at each hook (R37②). */
    private final List<Long> fixtureProjects = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Fixture-scoped teardown (R37②): the old blanket
        // TRUNCATE tp_user, tp_project CASCADE also wiped the shipped factory
        // rows — tp_tool/tp_skill/tp_asset hang off tp_project through that
        // cascade, which is how the slot database lost its baseline (2026-11-12
        // tp_tool 0/12 ledger). Judge tables keep the t278j7 prefix sweep
        // (never touch other suites' in-flight judge rows).
        jdbcTemplate.update("DELETE FROM tp_judge_review WHERE run_id IN "
                + "(SELECT id FROM tp_judge_run WHERE target_ref LIKE 't278j7%')");
        jdbcTemplate.update("DELETE FROM tp_judge_record WHERE run_id IN "
                + "(SELECT id FROM tp_judge_run WHERE target_ref LIKE 't278j7%')");
        jdbcTemplate.update("DELETE FROM tp_judge_run WHERE target_ref LIKE 't278j7%'");
        jdbcTemplate.update("DELETE FROM tp_judge_sample WHERE set_name LIKE 't278j7s.%'");
        jdbcTemplate.update("DELETE FROM tp_judge_rubric WHERE name LIKE 't278j7k.%'");
        jdbcTemplate.update("DELETE FROM tp_llm_config WHERE name LIKE 't278j7m.%'");
        setBootstrapQuiet("200");
        // Fixture testers carry the unique t278j7tester.<pid> username, so a
        // prefix sweep also clears crashed runs' leftovers; their two link
        // tables hang off tp_user through NO ACTION FKs, hence children first.
        jdbcTemplate.update("DELETE FROM tp_user_role WHERE user_id IN "
                + "(SELECT id FROM tp_user WHERE username LIKE 't278j7tester.%')");
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE user_id IN "
                + "(SELECT id FROM tp_user WHERE username LIKE 't278j7tester.%')");
        jdbcTemplate.update("DELETE FROM tp_user WHERE username LIKE 't278j7tester.%'");
        sweepPlanCases(fixtureProjects);
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, fixtureProjects);
        fixtureProjects.clear();
    }

    /**
     * tp_plan_case keys on plan_id without an FK into tp_exec_plan, so the
     * generic project sweep cannot walk there — delete it through the plan's
     * project id first.
     */
    private void sweepPlanCases(List<Long> projectIds) {
        for (Long pid : projectIds) {
            jdbcTemplate.update("DELETE FROM tp_plan_case WHERE plan_id IN "
                    + "(SELECT id FROM tp_exec_plan WHERE project_id = ?)", pid);
        }
    }


    private long createProject(String code) {
        // Re-sweep a same-code leftover of a crashed run before recreating —
        // no blanket table wipe is ever needed (R37②).
        long stale = SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, code);
        if (stale > 0) {
            sweepPlanCases(List.of(stale));
            SharedDbFixtureSweep.byProject(jdbcTemplate, stale);
        }
        long pid = projectService.create(1L, new ProjectCreateInput("门禁项目" + code, code,
                "zh-CN", "P1", null));
        fixtureProjects.add(pid);
        return pid;
    }

    private long createEnv(long pid, String name) {
        return projectEnvService.create(pid, new ProjectEnvInput(name, 2,
                "https://" + name + ".example.com", null, "web", "chromium", "v1.0.0"));
    }

    private long createCase(long pid, String title, int phase) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, title, "前置", "预期",
                1, 2, "web", 3, phase, 1, null, List.of()));
        return info.id();
    }

    private long createTester(String username, long pid) {
        long uid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, 'x', ?, 1, 1) RETURNING id
                """, Long.class, username, username);
        long roleId = roleService.roles().stream().filter(r -> r.code().equals("tester"))
                .map(RoleInfo::id).findFirst().orElseThrow();
        roleService.assignUserRoles(uid, List.of(roleId));
        memberService.add(pid, uid, 1);
        return uid;
    }

    
    private PlanInfo readyPlan(long pid, long env1, ExitCriteria exit) {
        long smoke = createCase(pid, "冒烟", 1);
        long c1 = createCase(pid, "门禁用例A", 2);
        long c2 = createCase(pid, "门禁用例B", 2);
        long c3 = createCase(pid, "门禁用例C", 3);
        jdbcTemplate.update("UPDATE tp_test_case SET last_exec_result = 1 WHERE id = ?", smoke);
        PlanInfo plan = planService.create(1L, new PlanCreateInput(pid, "t278j7 门禁计划",
                "v1.0", null, List.of(env1),
                CaseScopeRule.of(List.of(2, 3), List.of(1), null, List.of()),
                EntryCriteria.of(true), exit, "2026-10-01", "2026-10-10", null));
        long tester = createTester("t278j7tester." + pid, pid);
        planService.assignCases(1L, plan.id(), new PlanAssignInput(
                planService.listCases(plan.id(), null).stream()
                        .map(PlanCaseInfo::caseId).toList(), tester));
        planService.publish(1L, plan.id());
        for (long cid : List.of(c1, c2, c3)) {
            scriptService.save(new ScriptSaveInput(cid, "v2",
                    "{\"caseId\": " + cid + ", \"setup\": [], \"steps\": []}", 1));
        }
        planService.generateBatches(1L, plan.id());
        for (PlanCaseInfo r : planService.listCases(plan.id(), null)) {
            jdbcTemplate.update("UPDATE tp_plan_case SET status = 3 WHERE id = ?", r.id());
        }
        return planService.get(plan.id());
    }


    private RubricView fixtureRubric(String key) {
        return judgeRubricService.save(new RubricSaveInput("t278j7k." + key, "用例生成",
                List.of(new RubricDimension("相关性", "是否切题", 1.0, List.of())),
                List.of(), 60, new BigDecimal("0.400"), new BigDecimal("0.600"), 9101L));
    }

    
    private RunView finishedRun(String tag, RubricView rubric, Long cfg) {
        EvalSetVersionView set = judgeSampleService.publish("t278j7s." + tag, List.of(
                new SampleInput("需求A", "用例A", List.of(), "manual", 0),
                new SampleInput("需求B", "用例B", List.of(), "manual", 0),
                new SampleInput("需求C", "用例C", List.of(), "manual", 0)), 9101L);
        RunView run = judgeRunService.createRun(new RunCreateInput(rubric.id(),
                set.setName(), set.version(), null, "pipeline", "t278j7-" + tag, cfg, 9101L));
        List<Long> sids = judgeSampleService.listSamples(set.setName(), set.version()).stream()
                .map(SampleView::id).sorted().toList();
        int[] scores = {90, 90, 50};
        for (int i = 0; i < sids.size(); i++) {
            judgeRecordService.saveScore(new RecordInput(run.id(), sids.get(i),
                    scores[i], List.of("json-valid 通过"), Map.of("相关性", (double) scores[i]),
                    Map.of("相关性", "有据（引用锚 offset 0-8）"), 100L, 300));
        }
        judgeRunService.start(run.id());
        judgeRunService.finish(run.id(), true);
        return run;
    }

    
    private void agreeReviews(RunView run) {
        List<Long> recIds = jdbcTemplate.queryForList(
                "SELECT id FROM tp_judge_record WHERE run_id = ? ORDER BY id",
                Long.class, run.id());
        List<Integer> finals = jdbcTemplate.queryForList(
                "SELECT final_score FROM tp_judge_record WHERE run_id = ? ORDER BY id",
                Integer.class, run.id());
        for (int i = 0; i < recIds.size(); i++) {
            judgeReviewService.recordReview(new JudgeReviewInput(recIds.get(i), 9102L, null,
                    finals.get(i), "测试改判（与机器分一致）"));
        }
    }

    private void setBootstrapQuiet(String value) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM tp_sys_config WHERE config_key = ?", Long.class, BOOTSTRAP_KEY);
        ConfigInput input = new ConfigInput(BOOTSTRAP_KEY, value, "AI评测", "int", 0,
                "校准基线所需改判配对数（测试调值，测末恢复）");
        if (ids.isEmpty()) {
            configService.create(9101L, input);
        } else {
            configService.update(9101L, ids.get(0), input);
        }
    }

    private Long newModel(String tag) {
        return jdbcTemplate.queryForObject("INSERT INTO tp_llm_config (name, provider, model_name) "
                + "VALUES ('t278j7m." + tag + "', 'openai', 'judge-x') RETURNING id", Long.class);
    }


    @Test
    void 门禁开_族内无已完成运行_拦且人话点名先跑评测() {
        setBootstrapQuiet("1");
        long pid = createProject("J7A");
        long env1 = createEnv(pid, "门禁环境A");
        RubricView rubric = fixtureRubric("gateA");
        PlanInfo plan = readyPlan(pid, env1,
                ExitCriteria.withEvalGate(0.95, true, rubric.name(), "用例生成", 0.6));

        CompletionCheckView check = planService.completionCheck(1L, plan.id());
        assertFalse(check.passed(), "族内无评测运行不得放行准出");
        assertTrue(check.message().contains("评测") && check.message().contains("已完成"),
                "人话点名「尚无已完成评测运行」：实际=" + check.message());
        // Guidance must name the real page entries (evaluation doors shipped;
        // the rejection message points operators to where the run is created).
        assertTrue(check.message().contains("评测集") && check.message().contains("评测运行"),
                "指路必须点名「评测集」建卷与「评测运行」发起入口：实际=" + check.message());
        assertEquals(3, planService.get(plan.id()).status(), "拦截后保持执行中不自动完成");
    }

    @Test
    void 达线率低于门禁阈值_拦且阈值回显人话() {
        setBootstrapQuiet("1");
        long pid = createProject("J7B");
        long env1 = createEnv(pid, "门禁环境B");
        RubricView rubric = fixtureRubric("gateB");
        Long cfg = newModel("b1");
        finishedRun("b", rubric, cfg);
        PlanInfo plan = readyPlan(pid, env1,
                ExitCriteria.withEvalGate(0.95, true, rubric.name(), "用例生成", 0.8));

        CompletionCheckView check = planService.completionCheck(1L, plan.id());
        assertFalse(check.passed(), "达线率 66.7% < 门禁 80% 不得放行");
        assertTrue(check.message().contains("达线率"), "人话点名达线率：实际=" + check.message());
        assertTrue(check.message().contains("80"), "门禁阈值回显：实际=" + check.message());
    }

    @Test
    void 达线率达标_但一致率无配对_参考分禁入准出_Jb() {
        setBootstrapQuiet("1");
        long pid = createProject("J7C");
        long env1 = createEnv(pid, "门禁环境C");
        RubricView rubric = fixtureRubric("gateC");
        Long cfg = newModel("c1");
        finishedRun("c", rubric, cfg);
        PlanInfo plan = readyPlan(pid, env1,
                ExitCriteria.withEvalGate(0.95, true, rubric.name(), "用例生成", 0.6));

        CompletionCheckView check = planService.completionCheck(1L, plan.id());
        assertFalse(check.passed(), "无改判配对=一致率未确认（J-b 参考分），禁入准出");
        assertTrue(check.message().contains("一致率") || check.message().contains("参考分"),
                "人话点名一致率/参考分：实际=" + check.message());
    }

    @Test
    void 改判配对达标_门禁放行_准出达标自动完成() {
        setBootstrapQuiet("1");
        long pid = createProject("J7D");
        long env1 = createEnv(pid, "门禁环境D");
        RubricView rubric = fixtureRubric("gateD");
        Long cfg = newModel("d1");
        RunView run = finishedRun("d", rubric, cfg);
        PlanInfo plan = readyPlan(pid, env1,
                ExitCriteria.withEvalGate(0.95, true, rubric.name(), "用例生成", 0.6));
        assertFalse(planService.completionCheck(1L, plan.id()).passed(), "补配对前应拦");

        agreeReviews(run);
        CompletionCheckView done = planService.completionCheck(1L, plan.id());
        assertTrue(done.passed(), "达线率达标+κ 达标应放行：实际=" + done.message());
        assertEquals(4, planService.get(plan.id()).status(), "门禁放行后自动完成");
    }

    @Test
    void 老准出JSON缺门禁字段_默认关不炸既有判定() {
        long pid = createProject("J7E");
        long env1 = createEnv(pid, "门禁环境E");
        PlanInfo plan = readyPlan(pid, env1, ExitCriteria.of(0.95, true));
        jdbcTemplate.update("UPDATE tp_exec_plan SET exit_criteria_json = "
                + "'{\"minPassRate\": 0.95, \"noOpenDefects\": true}'::jsonb WHERE id = ?",
                plan.id());

        CompletionCheckView check = planService.completionCheck(1L, plan.id());
        assertTrue(check.passed(), "老行门禁默认关——通过率 100% 照常放行：实际=" + check.message());
        assertEquals(4, planService.get(plan.id()).status());
    }

    @Test
    void 门禁写侧参数闸_族名空与阈值空或越界_人话拒() {
        RubricView rubric = fixtureRubric("gateF");

        ServiceException ex1 = assertThrows(ServiceException.class,
                () -> ExitCriteria.withEvalGate(0.95, true, " ", "用例生成", 0.6));
        assertTrue(ex1.getMessage().contains("评分标准"), "缺标准人话：实际=" + ex1.getMessage());

        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> ExitCriteria.withEvalGate(0.95, true, rubric.name(), "用例生成", 1.5));
        assertTrue(ex2.getMessage().contains("0~1"), "阈值区间人话：实际=" + ex2.getMessage());

        ServiceException ex3 = assertThrows(ServiceException.class,
                () -> ExitCriteria.withEvalGate(0.95, true, rubric.name(), "用例生成", null));
        assertTrue(ex3.getMessage().contains("达线率"), "阈值必填人话（禁静默虚绿）：实际="
                + ex3.getMessage());
    }
}
