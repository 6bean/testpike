package com.sixbean.testpike.core.plans.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.apitest.domain.service.ApiCollectionService;
import com.sixbean.testpike.core.apitest.domain.service.ApiRequestService;
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
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan-exit contract-coverage gate (W1c improvement item 5, closing step of the
 * AH batch).
 * <p>K8 key {@code plan.exit.apitest.min-coverage} (int percent, shipped default
 * 0 = gate off = zero drift for every existing plan). When on, completionCheck
 * measures the project-wide contract-endpoint coverage through the inventory
 * read path (denominator = non-deprecated endpoints of the anchor version of
 * every contract name chain, one source with the overview card — W-c2) and
 * refuses exit below the threshold with a plain-language message. Four spectra:
 * gate off keeps the verdict byte-identical and reports no measurement;
 * gate on below threshold blocks with the percent named; gate on at threshold
 * passes; gate on with no contract data at all blocks honestly (a gate cannot
 * pass on an empty denominator).
 * <p>Real dev database, no mocks: contracts land through the live upload chain,
 * the covering asset through the real collection/request save path (the folded
 * contract_endpoint_key column is what the coverage numerator counts).
 */
@SpringBootTest(classes = CoreApplication.class)
class PlanExitApitestCoverageGateTest {

    private static final String COVERAGE_KEY = "plan.exit.apitest.min-coverage";
    private static final String FIXTURE_PREFIX = "t373cov";

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
    private ScriptService scriptService;
    @Autowired
    private ApiSpecService apiSpecService;
    @Autowired
    private ApiCollectionService apiCollectionService;
    @Autowired
    private ApiRequestService apiRequestService;
    @Autowired
    private ConfigService configService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Coverage-gate fixture projects tracked for the per-hook sweep (R37②). */
    private final List<Long> coverageProjects = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM tp_apitest_request WHERE collection_id IN "
                + "(SELECT id FROM tp_apitest_collection WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE ?))", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tp_apitest_collection WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE ?)", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tp_api_spec WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE ?)", FIXTURE_PREFIX + "%");
        setCoverageThresholdQuiet(null);
        // Fixture-scoped teardown (R37②): the old blanket TRUNCATE also wiped
        // the shipped factory rows hanging off tp_project through its cascade.
        // Testers carry the underscore-free t373covtester.<pid> username, so
        // the prefix sweep also clears crashed runs' leftovers.
        SharedDbFixtureSweep.usersByPatternDown(jdbcTemplate, FIXTURE_PREFIX + "tester.%");
        SharedDbFixtureSweep.plansTreeDown(jdbcTemplate,
                SharedDbFixtureSweep.SPEC_FAMILY_EXTRA_TABLES, coverageProjects);
        coverageProjects.clear();
    }

    // ===== fixtures (same posture as PlanExitChangeReviewWarnTest) =====

    private long createProject(String code) {
        // Same-code leftover re-sweep + real service create + id tracking:
        // single source SharedDbFixtureSweep (R40/R37②).
        return SharedDbFixtureSweep.createPlanFixtureProject(projectService, jdbcTemplate,
                SharedDbFixtureSweep.SPEC_FAMILY_EXTRA_TABLES, "覆盖率项目", code, coverageProjects);
    }

    private long createEnv(long pid, String name) {
        // Fixture env URL on .invalid (RFC 2676 reserved): the dev profile sets
        // TESTPIKE_PROD_BLACKLIST=*.example.com, and generateBatches→exec-task
        // create must not trip the production-domain guard for synthetic URLs.
        return projectEnvService.create(pid, new ProjectEnvInput(name, 2,
                "https://" + name + ".t373cov.invalid", null, "web", "chromium", "v1.0.0"));
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
        long roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_role WHERE code = 'tester'", Long.class);
        jdbcTemplate.update("INSERT INTO tp_user_role (user_id, role_id) VALUES (?, ?)", uid, roleId);
        memberService.add(pid, uid, 1);
        return uid;
    }

    /** Plan whose other exit criteria are fully met: 3 phase-2/3 cases all passed. */
    private PlanInfo readyPlan(long pid, long env1) {
        createCase(pid, "覆盖率用例A", 2);
        createCase(pid, "覆盖率用例B", 2);
        createCase(pid, "覆盖率用例C", 3);
        PlanInfo plan = planService.create(1L, new PlanCreateInput(pid, FIXTURE_PREFIX + " 覆盖率计划",
                "v1.0", null, List.of(env1),
                CaseScopeRule.of(List.of(2, 3), List.of(1), null, List.of()),
                EntryCriteria.of(true), ExitCriteria.of(0.95, false), "2026-10-01", "2026-10-10", null));
        long tester = createTester(FIXTURE_PREFIX + "tester." + pid, pid);
        planService.assignCases(1L, plan.id(), new PlanAssignInput(
                planService.listCases(plan.id(), null).stream()
                        .map(PlanCaseInfo::caseId).toList(), tester));
        planService.publish(1L, plan.id());
        List<Long> caseIds = jdbcTemplate.queryForList(
                "SELECT id FROM tp_test_case WHERE project_id = ?", Long.class, pid);
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

    /** Contract with exactly two live endpoints: GET /pets and POST /pets. */
    private static final String SPEC_TWO_ENDPOINTS = """
            {"openapi":"3.0.1","info":{"title":"覆盖率契约","version":"1.0"},
             "paths":{
               "/pets":{"get":{"operationId":"listPets",
                   "responses":{"200":{"description":"ok"}}},
                 "post":{"operationId":"createPet","responses":{"201":{"description":"c"}}}}
             }}
            """;

    private void landContract(long pid) {
        apiSpecService.upload(1L, pid, FIXTURE_PREFIX + "-pet-api",
                SPEC_TWO_ENDPOINTS.getBytes(StandardCharsets.UTF_8), null);
    }

    /** One saved request on GET /pets — covers 1 of the 2 contract endpoints (50%). */
    private ApiRequestInfo coverGetPets(long pid) {
        long collectionId = apiCollectionService.create(1L,
                new CollectionSaveInput(pid, 0L, FIXTURE_PREFIX + "-集")).id();
        return apiRequestService.create(1L, new RequestSaveInput(collectionId,
                "查宠物列表", "GET", "/pets", null, null, null, null));
    }

    /** Hot-update the K8 threshold through the real ConfigService chain. */
    private void setCoverageThresholdQuiet(String value) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM tp_sys_config WHERE config_key = ?", Long.class, COVERAGE_KEY);
        String effective = value == null ? "0" : value;
        ConfigInput input = new ConfigInput(COVERAGE_KEY, effective, "API测试", "int", 0,
                "计划准出的接口契约端点覆盖率下限百分比（0~100）：0=不校验（默认）；"
                        + ">0=准出前实测本项目契约端点覆盖率，低于下限则拒并点名实际值，热更新即时生效");
        if (ids.isEmpty()) {
            configService.create(9101L, input);
        } else {
            configService.update(9101L, ids.get(0), input);
        }
    }

    // ===== assertion surface =====

    @Test
    void gateOffByDefaultKeepsTheVerdictUntouched() {
        setCoverageThresholdQuiet("0");
        long pid = createProject(FIXTURE_PREFIX + "OFF");
        long env1 = createEnv(pid, "covOFF");
        landContract(pid); // 0% covered — would block if the gate were on
        PlanInfo plan = readyPlan(pid, env1);

        CompletionCheckView check = planService.completionCheck(1L, plan.id());
        assertTrue(check.passed(), "默认关态不得拦截：实际=" + check.message());
        assertEquals(-1, check.apitestCoveragePercent(), "关态不测覆盖率，回报未评量");
        assertFalse(check.message().contains("覆盖率"), "关态消息零漂移：实际=" + check.message());
    }

    @Test
    void gateOnBelowThresholdBlocksAndNamesThePercent() {
        setCoverageThresholdQuiet("80");
        long pid = createProject(FIXTURE_PREFIX + "LOW");
        long env1 = createEnv(pid, "covLOW");
        landContract(pid);
        coverGetPets(pid); // 1 of 2 endpoints = 50% < 80
        PlanInfo plan = readyPlan(pid, env1);

        CompletionCheckView check = planService.completionCheck(1L, plan.id());
        assertFalse(check.passed(), "50% 覆盖率应被 80 下限拦下");
        assertEquals(80, check.minApitestCoverage(), "生效下限应回报");
        assertEquals(50, check.apitestCoveragePercent(), "实测覆盖率应回报");
        assertTrue(check.message().contains("覆盖率"), "人话点名覆盖率：实际=" + check.message());
        assertTrue(check.message().contains("80"), "点名阈值 80：实际=" + check.message());
    }

    @Test
    void gateOnAtThresholdPasses() {
        setCoverageThresholdQuiet("50");
        long pid = createProject(FIXTURE_PREFIX + "MEET");
        long env1 = createEnv(pid, "covMEET");
        landContract(pid);
        coverGetPets(pid); // exactly 50% = threshold, boundary passes
        PlanInfo plan = readyPlan(pid, env1);

        CompletionCheckView check = planService.completionCheck(1L, plan.id());
        assertTrue(check.passed(), "达标线含等号应放行：实际=" + check.message());
        assertEquals(50, check.apitestCoveragePercent());
    }

    @Test
    void gateOnWithoutAnyContractBlocksHonestly() {
        setCoverageThresholdQuiet("80");
        long pid = createProject(FIXTURE_PREFIX + "NOSPEC");
        long env1 = createEnv(pid, "covNOSPEC");
        PlanInfo plan = readyPlan(pid, env1);

        CompletionCheckView check = planService.completionCheck(1L, plan.id());
        assertFalse(check.passed(), "无契约数据时分母为空，禁假装覆盖率达标");
        assertTrue(check.message().contains("契约"), "点名缺契约地基：实际=" + check.message());
    }

    @Test
    void invalidConfigValueIsTreatedAsGateOff() {
        setCoverageThresholdQuiet("abc");
        long pid = createProject(FIXTURE_PREFIX + "BAD");
        long env1 = createEnv(pid, "covBAD");
        landContract(pid);
        PlanInfo plan = readyPlan(pid, env1);

        CompletionCheckView check = planService.completionCheck(1L, plan.id());
        assertTrue(check.passed(), "非法值按停用处理（存量零漂移姿态），不得炸准出：实际=" + check.message());
        assertEquals(-1, check.apitestCoveragePercent());
    }
}
