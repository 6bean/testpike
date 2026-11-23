package com.sixbean.testpike.core.plans.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.plans.api.dto.CaseScopeRule;
import com.sixbean.testpike.core.plans.api.dto.CompletionCheckView;
import com.sixbean.testpike.core.plans.api.dto.EntryCriteria;
import com.sixbean.testpike.core.plans.api.dto.ExitCriteria;
import com.sixbean.testpike.core.plans.api.dto.PlanAssignInput;
import com.sixbean.testpike.core.plans.api.dto.PlanCaseInfo;
import com.sixbean.testpike.core.plans.api.dto.PlanCreateInput;
import com.sixbean.testpike.core.plans.api.dto.PlanInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.MemberService;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.rbac.api.dto.RoleInfo;
import com.sixbean.testpike.core.rbac.domain.service.RoleService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecInfo;
import com.sixbean.testpike.core.spec.domain.service.ApiSpecService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan-exit hint slot for the contract change-review queue (W1c .
 * <p>A plan whose exit criteria are met completes as usual; the switch
 * {@code api-spec.exit.change-review.warn} (K8, hot-reload, default off) only adds
 * a plain-language hint carrying the project's pending change-review count — it
 * never blocks exit. Three spectra asserted: switch off keeps the message
 * byte-identical to today, switch on with pending rows yields reviewWarn plus the
 * pendingSpecChanges number while passed stays true, switch on without pending
 * rows yields a null warn (no noise on a clean queue).
 * <p>Real dev database, no mocks. Pending rows are produced through the live
 * contract upload chain (three versions V1→V2→V3, each landing a non-empty
 * endpoint diff so the queue gets two pending rows), and the K8 key is
 * hot-updated through the real ConfigService chain with the value restored to
 * its shipped default at test end.
 */
@SpringBootTest(classes = CoreApplication.class)
class PlanExitChangeReviewWarnTest {

    private static final String WARN_KEY = "api-spec.exit.change-review.warn";
    private static final String FIXTURE_PREFIX = "t371cw";

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
    private ApiSpecService apiSpecService;
    @Autowired
    private ConfigService configService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Warn-gate fixture projects, swept by id tree from both lifecycle hooks (R37②). */
    private final List<Long> warnProjects = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Queue + spec tables scoped to this fixture prefix only (never wipe other
        // slices' in-flight rows); plan domain swept fixture-scoped like
        // PlanExitJudgeGateTest — the old blanket TRUNCATE also wiped the shipped
        // factory rows hanging off tp_project through its cascade (R37②).
        jdbcTemplate.update("DELETE FROM tp_api_spec_change_analysis WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE ?)", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tp_api_spec WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE ?)", FIXTURE_PREFIX + "%");
        setWarnSwitchQuiet(null);
        // Fixture testers carry the underscore-free t371cwtester.<pid> username,
        // so the prefix sweep also clears crashed runs' leftovers.
        SharedDbFixtureSweep.usersByPatternDown(jdbcTemplate, FIXTURE_PREFIX + "tester.%");
        SharedDbFixtureSweep.plansTreeDown(jdbcTemplate, SharedDbFixtureSweep.SPEC_FAMILY_EXTRA_TABLES, warnProjects);
        warnProjects.clear();
    }

    // ===== fixtures (same posture as PlanExitJudgeGateTest) =====

    private long createProject(String code) {
        // Same-code leftover re-sweep + real service create + id tracking:
        // single source SharedDbFixtureSweep (R40/R37②).
        return SharedDbFixtureSweep.createPlanFixtureProject(projectService, jdbcTemplate,
                SharedDbFixtureSweep.SPEC_FAMILY_EXTRA_TABLES, "提示位项目", code, warnProjects);
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

    /** Plan whose exit criteria are fully met: 3 cases all passed, no defects. */
    private PlanInfo readyPlan(long pid, long env1) {
        long smoke = createCase(pid, "冒烟", 1);
        createCase(pid, "提示位用例A", 2);
        createCase(pid, "提示位用例B", 2);
        createCase(pid, "提示位用例C", 3);
        jdbcTemplate.update("UPDATE tp_test_case SET last_exec_result = 1 WHERE id = ?", smoke);
        PlanInfo plan = planService.create(1L, new PlanCreateInput(pid, FIXTURE_PREFIX + " 提示位计划",
                "v1.0", null, List.of(env1),
                CaseScopeRule.of(List.of(2, 3), List.of(1), null, List.of()),
                EntryCriteria.of(true), ExitCriteria.of(0.95, false), "2026-10-01", "2026-10-10", null));
        long tester = createTester(FIXTURE_PREFIX + "tester." + pid, pid);
        planService.assignCases(1L, plan.id(), new PlanAssignInput(
                planService.listCases(plan.id(), null).stream()
                        .map(PlanCaseInfo::caseId).toList(), tester));
        planService.publish(1L, plan.id());
        List<Long> caseIds = jdbcTemplate.queryForList(
                "SELECT id FROM tp_test_case WHERE project_id = ? AND phase IN (2,3)",
                Long.class, pid);
        for (long cid : caseIds) {
            scriptService.save(new ScriptSaveInput(cid, "v2",
                    "{\"caseId\": " + cid + ", \"setup\": [], \"steps\": []}", 1));
        }
        planService.generateBatches(1L, plan.id());
        for (PlanCaseInfo r : planService.listCases(plan.id(), null)) {
            jdbcTemplate.update("UPDATE tp_plan_case SET status = 3 WHERE id = ?", r.id());
        }
        return planService.get(plan.id());
    }

    private static final String V1 = """
            {"openapi":"3.0.1","info":{"title":"提示位契约","version":"1.0"},
             "paths":{
               "/pets":{"get":{"operationId":"listPets",
                   "responses":{"200":{"description":"ok"}}},
                 "post":{"operationId":"createPet","responses":{"201":{"description":"c"}}}}
             }}
            """;

    /** v2 removes POST /pets and adds GET /health — a non-empty endpoint diff. */
    private static final String V2 = """
            {"openapi":"3.0.1","info":{"title":"提示位契约","version":"1.1"},
             "paths":{
               "/pets":{"get":{"operationId":"listPets",
                   "responses":{"200":{"description":"ok"}}}},
               "/health":{"get":{"operationId":"health","responses":{"200":{"description":"ok"}}}}
             }}
            """;

    /** v3 adds PATCH /pets — a second non-empty diff against v2. */
    private static final String V3 = """
            {"openapi":"3.0.1","info":{"title":"提示位契约","version":"1.2"},
             "paths":{
               "/pets":{"get":{"operationId":"listPets",
                   "responses":{"200":{"description":"ok"}}},
                 "patch":{"operationId":"patchPet","responses":{"200":{"description":"ok"}}}},
               "/health":{"get":{"operationId":"health","responses":{"200":{"description":"ok"}}}}
             }}
            """;

    /**
     * Drive the live upload chain through three versions so the queue holds exactly
     * two pending rows (one per landed (old, new) pair).
     */
    private ApiSpecInfo landPendingChanges(long pid) {
        apiSpecService.upload(1L, pid, FIXTURE_PREFIX + "-pet-api",
                V1.getBytes(StandardCharsets.UTF_8), null);
        apiSpecService.upload(1L, pid, FIXTURE_PREFIX + "-pet-api",
                V2.getBytes(StandardCharsets.UTF_8), null);
        return apiSpecService.upload(1L, pid, FIXTURE_PREFIX + "-pet-api",
                V3.getBytes(StandardCharsets.UTF_8), null);
    }

    /**
     * Restore the K8 switch to its shipped default through the real ConfigService
     * chain (K8 posture: the seed row survives, only the value returns to 0).
     */
    private void setWarnSwitchQuiet(String value) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM tp_sys_config WHERE config_key = ?", Long.class, WARN_KEY);
        String effective = value == null ? "0" : value;
        ConfigInput input = new ConfigInput(WARN_KEY, effective, "API测试", "int", 0,
                "计划准出检查的契约变更待复核提示开关：0=不提示（默认）；非0=准出结果附带待复核条数提示，只提示不拦截准出，热更新即时生效");
        if (ids.isEmpty()) {
            configService.create(9101L, input);
        } else {
            configService.update(9101L, ids.get(0), input);
        }
    }

    // ===== assertion surface =====

    @Test
    void 开关关_有待复核_消息零漂移无提示() {
        setWarnSwitchQuiet("0");
        long pid = createProject(FIXTURE_PREFIX + "OFF");
        long env1 = createEnv(pid, "提示位环境OFF");
        landPendingChanges(pid);
        PlanInfo plan = readyPlan(pid, env1);

        CompletionCheckView check = planService.completionCheck(1L, plan.id());
        assertTrue(check.passed(), "开关关且队列有待复核时准出照常放行：实际=" + check.message());
        assertNull(check.reviewWarn(), "开关关不得产生提示文案");
        assertTrue(check.message().contains("准出达标"), "达标消息保持原样：实际=" + check.message());
        // Zero-drift guard: with the switch off the message must not mention the queue.
        assertTrue(!check.message().contains("待复核"), "开关关消息禁含「待复核」：实际=" + check.message());
    }

    @Test
    void 开关开_有待复核_放行且人话提示含条数() {
        setWarnSwitchQuiet("1");
        long pid = createProject(FIXTURE_PREFIX + "ON");
        long env1 = createEnv(pid, "提示位环境ON");
        landPendingChanges(pid);
        PlanInfo plan = readyPlan(pid, env1);

        CompletionCheckView check = planService.completionCheck(1L, plan.id());
        assertTrue(check.passed(), "提示位只提示不拦截，准出照常放行：实际=" + check.message());
        assertEquals(2, check.pendingSpecChanges(), "V1→V2→V3 经真实上传链应产 2 条待复核：实际="
                + check.pendingSpecChanges());
        assertTrue(check.reviewWarn() != null && check.reviewWarn().contains("待复核 2"),
                "人话提示须含「待复核 2」：实际=" + check.reviewWarn());
    }

    @Test
    void 开关开_无待复核_提示为空不刷屏() {
        setWarnSwitchQuiet("1");
        long pid = createProject(FIXTURE_PREFIX + "CLEAR");
        long env1 = createEnv(pid, "提示位环境CLEAR");
        PlanInfo plan = readyPlan(pid, env1);

        CompletionCheckView check = planService.completionCheck(1L, plan.id());
        assertTrue(check.passed(), "队列干净时照常放行：实际=" + check.message());
        assertEquals(0, check.pendingSpecChanges(), "无契约版本=0 条待复核");
        assertNull(check.reviewWarn(), "队列干净不得输出空提示刷屏");
    }
}
