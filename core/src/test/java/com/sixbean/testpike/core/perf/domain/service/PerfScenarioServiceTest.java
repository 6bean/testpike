package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.component.EngineAdapter;
import com.sixbean.testpike.core.perf.domain.component.PerfConfigReader;
import com.sixbean.testpike.core.perf.domain.component.PerfMetricFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfScenarioServiceTest {

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private PerfConfigReader perfConfigReader;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;
    private String projectCode;

    @BeforeEach
    void seedFixtures() {
        cleanupRows();
        long uniq = System.nanoTime() % 1_000_000_000L;
        projectCode = "PERFSCN" + uniq;
        // Ids are read back through RETURNING so a foreign fixture row with an
        // overridden id can never out-rank max(id) and re-parent these rows.
        projectId = com.sixbean.testpike.core.support.FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "压测场景测试项目_" + uniq, projectCode);
        envId = com.sixbean.testpike.core.support.FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '性能-场景测', 1, 'http://perf-target.local/api')",
                projectId);
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, allowed_modes) VALUES (?, ?, 1000, '[]'::jsonb)", projectId, envId);
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
    }

    private void cleanupRows() {
        // Approval tickets carry an FK on project (and a scenario leg the A-13 door
        // nulls out); they are fixture rows too — clear them before the parent rows.
        jdbcTemplate.update("DELETE FROM tp_perf_approval WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFSCN%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFSCN%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFSCN%')");
        // Seeded pool rows hang off the same project; scenario-first is the FK order.
        jdbcTemplate.update("DELETE FROM tp_perf_dataset WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFSCN%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFSCN%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFSCN%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PERFSCN%'");
    }

    private static List<Map<String, Object>> requestSteps() {
        return List.of(Map.of("action", "http.request", "method", "POST", "url", "{{ENV:BASE}}/orders"));
    }


    @Test
    void constantRpsScenarioSavesWithPerVUserDefaultAndDriverLimitDisclosure() {
        PerfScenarioInput in = new PerfScenarioInput("恒定下单压测", envId, "CONSTANT_RPS",
                Map.of("targetRps", 50, "durationSeconds", 600), null, 5000, requestSteps(), null);
        long id = perfScenarioService.create(projectId, in);
        Map<String, Object> view = perfScenarioService.view(projectId, id);
        assertThat(view.get("pressure_model")).isEqualTo("CONSTANT_RPS");
        assertThat(view.get("login_strategy")).isEqualTo("PER_VUSER");
        assertThat(((Number) view.get("driver_limit")).intValue()).isEqualTo(5000);
        assertThat((String) view.get("steps_json")).contains("http.request");
    }

    @Test
    void rampAndSpikeModelsSave() {
        long r = perfScenarioService.create(projectId, new PerfScenarioInput("阶梯爬坡", envId, "RAMP",
                Map.of("startRps", 10, "peakRps", 300, "rampSeconds", 120, "holdSeconds", 60),
                null, null, requestSteps(), null));
        long s = perfScenarioService.create(projectId, new PerfScenarioInput("峰值脉冲", envId, "SPIKE",
                Map.of("baseRps", 20, "peakRps", 500, "durationSeconds", 300),
                "SHARED", null, requestSteps(), null));
        assertThat(perfScenarioService.view(projectId, r).get("pressure_model")).isEqualTo("RAMP");
        assertThat(perfScenarioService.view(projectId, s).get("pressure_model")).isEqualTo("SPIKE");
        assertThat(perfScenarioService.view(projectId, s).get("login_strategy")).isEqualTo("SHARED");
    }

    @Test
    void unknownPressureModelRejectedByName() {
        assertThatThrownBy(() -> perfScenarioService.create(projectId,
                new PerfScenarioInput("非法形态", envId, "STEP",
                        Map.of("targetRps", 1, "durationSeconds", 1), null, null, requestSteps(), null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("STEP")
                .hasMessageContaining("CONSTANT_RPS");
    }

    @Test
    void missingPressureConfigKeysRejectedInPlainWords() {
        assertThatThrownBy(() -> perfScenarioService.create(projectId,
                new PerfScenarioInput("缺参", envId, "CONSTANT_RPS",
                        Map.of("targetRps", 50), null, null, requestSteps(), null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("durationSeconds");
        assertThatThrownBy(() -> perfScenarioService.create(projectId,
                new PerfScenarioInput("峰值不倒挂", envId, "RAMP",
                        Map.of("startRps", 300, "peakRps", 10, "rampSeconds", 60, "holdSeconds", 60),
                        null, null, requestSteps(), null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("峰值");
    }

    // ---------- B-2 four new pressure models ----------

    @Test
    void fixedVuLoopsClosedModelSaves() {
        long id = perfScenarioService.create(projectId, new PerfScenarioInput("固定用户池闭环", envId, "FIXED_VU",
                Map.of("vu", 50, "thinkTimeMs", 1000, "loops", 20), null, null, requestSteps(), null));
        assertThat(perfScenarioService.view(projectId, id).get("pressure_model")).isEqualTo("FIXED_VU");
    }

    @Test
    void fixedVuWithoutTerminationRejectedNamingBothKeys() {
        // A closed user pool must declare when to stop: loops or durationSeconds, at least one.
        assertThatThrownBy(() -> perfScenarioService.create(projectId,
                new PerfScenarioInput("无终止条件", envId, "FIXED_VU",
                        Map.of("vu", 50), null, null, requestSteps(), null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("loops")
                .hasMessageContaining("durationSeconds");
    }

    @Test
    void ladderStepSearchModelSaves() {
        long id = perfScenarioService.create(projectId, new PerfScenarioInput("阶梯寻量", envId, "LADDER",
                Map.of("startRps", 10, "stepRps", 10, "stepSeconds", 60, "steps", 8),
                null, null, requestSteps(), null));
        assertThat(perfScenarioService.view(projectId, id).get("pressure_model")).isEqualTo("LADDER");
    }

    @Test
    void ladderMissingStepKeysRejected() {
        assertThatThrownBy(() -> perfScenarioService.create(projectId,
                new PerfScenarioInput("缺台阶参数", envId, "LADDER",
                        Map.of("startRps", 10, "stepSeconds", 60, "steps", 8),
                        null, null, requestSteps(), null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("stepRps");
    }

    @Test
    void businessCurveHourlyWeightsSaveAndDirtyWeightsRejected() {
        Map<String, Integer> curve = new java.util.HashMap<>();
        curve.put("durationSeconds", 86400);
        curve.put("peakRps", 200);
        curve.put("curve0", 30);
        curve.put("curve9", 80);
        curve.put("curve23", 20);
        long id = perfScenarioService.create(projectId, new PerfScenarioInput("业务时段曲线", envId,
                "BUSINESS_CURVE", curve, null, null, requestSteps(), null));
        assertThat(perfScenarioService.view(projectId, id).get("pressure_model")).isEqualTo("BUSINESS_CURVE");

        // No positive hour weight = a flat zero curve — refuse instead of compiling a no-load run.
        assertThatThrownBy(() -> perfScenarioService.create(projectId,
                new PerfScenarioInput("无权重", envId, "BUSINESS_CURVE",
                        Map.of("durationSeconds", 3600, "peakRps", 100),
                        null, null, requestSteps(), null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("curve");

        // Weight domain 0..100 (percent of peak): 120 names the offending hour.
        Map<String, Integer> dirty = new java.util.HashMap<>();
        dirty.put("durationSeconds", 3600);
        dirty.put("peakRps", 100);
        dirty.put("curve0", 120);
        assertThatThrownBy(() -> perfScenarioService.create(projectId,
                new PerfScenarioInput("权重越界", envId, "BUSINESS_CURVE", dirty,
                        null, null, requestSteps(), null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("curve0");
    }

    @Test
    void soakLongRunModelSavesWithHours() {
        long id = perfScenarioService.create(projectId, new PerfScenarioInput("长时浸泡", envId, "SOAK",
                Map.of("targetRps", 30, "durationHours", 12), null, null, requestSteps(), null));
        assertThat(perfScenarioService.view(projectId, id).get("pressure_model")).isEqualTo("SOAK");
    }

    @Test
    void unknownPressureModelRejectsListingAllSevenModels() {
        // The refusal names the full option set — the four B-2 models must appear (no stale 3-value message).
        assertThatThrownBy(() -> perfScenarioService.create(projectId,
                new PerfScenarioInput("非法形态", envId, "STEP",
                        Map.of("targetRps", 1, "durationSeconds", 1), null, null, requestSteps(), null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("FIXED_VU")
                .hasMessageContaining("LADDER")
                .hasMessageContaining("BUSINESS_CURVE")
                .hasMessageContaining("SOAK");
    }


    @Test
    void uiActionStepRejectedAtSaveNamingTheAction() {
        List<Map<String, Object>> mixed = List.of(
                Map.of("action", "http.request", "method", "GET", "url", "{{ENV:BASE}}/health"),
                Map.of("action", "click", "target", Map.of("css", "#buy")));
        assertThatThrownBy(() -> perfScenarioService.create(projectId,
                new PerfScenarioInput("混入UI", envId, "CONSTANT_RPS",
                        Map.of("targetRps", 10, "durationSeconds", 60), null, null, mixed, null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("click");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tp_perf_scenario WHERE project_id = ?",
                Long.class, projectId)).isZero();
    }

    /**
     * A-11: the perf step whitelist narrows to http.request only — the templates
     * compile the method/url slots and nothing else, so a sql.query /
     * console.assert step that passes the old six-action gate would compile into
     * an empty-URL fake call. Login stays a scenario-level D4 strategy plus the
     * auth-flagged http.request step (no separate LOGIN action), hence the
     * narrowed set names http.request only. Any other action is rejected at save
     * time in plain words naming both the offender and the allowed set.
     */
    @Test
    void nonHttpNonUiActionStepRejectedAtSaveNamingActionAndAllowedSet() {
        List<Map<String, Object>> sqlStep = List.of(
                Map.of("action", "sql.query", "sql", "SELECT 1"));
        assertThatThrownBy(() -> perfScenarioService.create(projectId,
                new PerfScenarioInput("压测SQL步_脏", envId, "CONSTANT_RPS",
                        Map.of("targetRps", 10, "durationSeconds", 60), null, null, sqlStep, null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("sql.query")
                .hasMessageContaining("http.request");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_perf_scenario WHERE project_id = ?",
                Long.class, projectId)).isZero();
    }

    // ---------- A-3: step field-domain guard (single source = NonUiActionContract) ----------

    /**
     * A perf step carrying an unknown expect key must fail at save: a mistyped
     * assertion key would be silently ignored at execution (assertion never
     * fires = fake confidence under load). The verdict comes from the shared
     * 04-contract validator — no second spec (red line 6).
     */
    @Test
    void saveRejectsStepWithUnknownExpectKeyNamingTheOffender() {
        List<Map<String, Object>> dirty = List.of(Map.of(
                "action", "http.request", "method", "POST", "url", "{{ENV:BASE}}/orders",
                "expect", Map.of("stats", Map.of("op", "eq", "expect", 200))));
        assertThatThrownBy(() -> perfScenarioService.create(projectId,
                new PerfScenarioInput("脏expect键", envId, "CONSTANT_RPS",
                        Map.of("targetRps", 10, "durationSeconds", 60), null, null, dirty, null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("stats");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_perf_scenario WHERE project_id = ?",
                Long.class, projectId)).isZero();
    }

    /** saveAs with an illegal variable name would break {{VAR:...}} resolution downstream. */
    @Test
    void saveRejectsStepWithDirtySaveAsName() {
        List<Map<String, Object>> dirty = List.of(Map.of(
                "action", "http.request", "method", "POST", "url", "{{ENV:BASE}}/orders",
                "saveAs", "my order id"));
        assertThatThrownBy(() -> perfScenarioService.create(projectId,
                new PerfScenarioInput("脏变量名", envId, "CONSTANT_RPS",
                        Map.of("targetRps", 10, "durationSeconds", 60), null, null, dirty, null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("saveAs");
    }

    /** An empty url compiles into a fake request against the base URL — rejected by name. */
    @Test
    void saveRejectsStepWithBlankUrl() {
        List<Map<String, Object>> dirty = List.of(Map.of(
                "action", "http.request", "method", "GET", "url", ""));
        assertThatThrownBy(() -> perfScenarioService.create(projectId,
                new PerfScenarioInput("空地址", envId, "CONSTANT_RPS",
                        Map.of("targetRps", 10, "durationSeconds", 60), null, null, dirty, null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("url");
    }

    // ---------- scale contract: scenario step ceiling via K8 key ----------

    /**
     * The save chain caps a scenario at perf.scenario.max-steps (K8, hot-updatable,
     * never hardcoded). Beyond the cap the save is rejected in plain words naming
     * both the current step count and the ceiling, and nothing reaches the table.
     */
    @Test
    void saveRejectsScenarioBeyondMaxStepsNamingCountAndCeiling() {
        seedStepCeiling("2");
        try {
            List<Map<String, Object>> steps = List.of(
                    Map.of("action", "http.request", "method", "GET", "url", "/a"),
                    Map.of("action", "http.request", "method", "GET", "url", "/b"),
                    Map.of("action", "http.request", "method", "GET", "url", "/c"));
            assertThatThrownBy(() -> perfScenarioService.create(projectId,
                    new PerfScenarioInput("超上限场景", envId, "CONSTANT_RPS",
                            Map.of("targetRps", 10, "durationSeconds", 60), null, null, steps, null)))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("3 步").hasMessageContaining("上限 2");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_perf_scenario WHERE project_id = ?",
                    Long.class, projectId)).isZero();
        } finally {
            seedStepCeiling("500");
        }
    }

    /** Ceiling default = 500 when the row is missing; a hot-written value wins immediately. */
    @Test
    void scenarioStepCeilingDefaults500AndFollowsHotUpdate() {
        Long seeded = configId("perf.scenario.max-steps");
        if (seeded != null) {
            configService.delete(1L, seeded);
        }
        assertThat(perfConfigReader.scenarioMaxSteps()).isEqualTo(500);
        seedStepCeiling("8");
        assertThat(perfConfigReader.scenarioMaxSteps()).isEqualTo(8);
        // A dirty row (0/negative/non-numeric) can never switch the cap off — falls back to 500.
        seedStepCeiling("0");
        assertThat(perfConfigReader.scenarioMaxSteps()).isEqualTo(500);
        seedStepCeiling("500");
    }

    /** Write (or overwrite) the step-ceiling config row; final state mirrors the V281 seed. */
    private void seedStepCeiling(String value) {
        Long id = configId("perf.scenario.max-steps");
        if (id != null) {
            configService.delete(1L, id);
        }
        configService.create(1L, new ConfigInput("perf.scenario.max-steps", value, "系统", "int", 0,
                "压测场景步骤数上限（默认 500；超限保存人话拒绝并点名当前步数与上限；热更即生效）"));
    }

    /** A legal DSL v2 step (method/url/expect/saveAs/auth) saves and reads back verbatim. */
    @Test
    void legalDslV2StepWithExpectAndSaveAsSaves() {
        List<Map<String, Object>> step = List.of(Map.of(
                "action", "http.request", "method", "POST", "url", "{{ENV:BASE}}/orders",
                "auth", false,
                "saveAs", "global.orderId",
                "expect", Map.of("status", 201,
                        "body", List.of(Map.of("path", "$.data.id",
                                "op", "contains", "expect", "ORD")))));
        long id = perfScenarioService.create(projectId, new PerfScenarioInput("合法v2步", envId,
                "CONSTANT_RPS", Map.of("targetRps", 10, "durationSeconds", 60),
                null, null, step, null));
        assertThat((String) perfScenarioService.view(projectId, id).get("steps_json"))
                .contains("saveAs").contains("expect");
    }

    // ---------- stage c B-3: mix keys ride the structural save gate ----------

    /**
     * A fully declared mix (every step carries transaction+weight) saves: the
     * save gate judges structure only (single source in
     * PerfStepActionGate.mixStructureViolation) — engine capability rides the
     * compile stage per engine (same split as the SHARED login strategy: the
     * weighted k6/Gatling mappings land in stages c-2β/c-2γ, while storing a
     * complete, unambiguous declaration is honest on its own).
     */
    @Test
    void fullyDeclaredMixRatioSavesAndStoresVerbatim() {
        List<Map<String, Object>> mixed = List.of(
                Map.of("action", "http.request", "method", "POST", "url", "{{ENV:BASE}}/orders",
                        "transaction", "下单链路", "weight", 80),
                Map.of("action", "http.request", "method", "GET", "url", "{{ENV:BASE}}/search",
                        "transaction", "查询链路", "weight", 20));
        long id = perfScenarioService.create(projectId,
                new PerfScenarioInput("配比放行_双组", envId, "CONSTANT_RPS",
                        Map.of("targetRps", 10, "durationSeconds", 60), null, null, mixed, null));
        assertThat((String) perfScenarioService.view(projectId, id).get("steps_json"))
                .contains("下单链路").contains("查询链路").contains("weight");
    }

    /**
     * Transaction grouping without weights is legal (the links split the
     * pressure evenly — a well-defined shape, not an ambiguity).
     */
    @Test
    void transactionWithoutWeightsSavesAsEvenSplit() {
        List<Map<String, Object>> grouped = List.of(
                Map.of("action", "http.request", "method", "GET", "url", "{{ENV:BASE}}/a",
                        "transaction", "链路A"),
                Map.of("action", "http.request", "method", "GET", "url", "{{ENV:BASE}}/b",
                        "transaction", "链路B"));
        long id = perfScenarioService.create(projectId,
                new PerfScenarioInput("配比放行_等分", envId, "CONSTANT_RPS",
                        Map.of("targetRps", 10, "durationSeconds", 60), null, null, grouped, null));
        assertThat(perfScenarioService.view(projectId, id).get("name")).isEqualTo("配比放行_等分");
    }

    /**
     * Partial declaration (some steps carry the mix keys, some do not) is
     * rejected naming the step: a half-declared mix would leave the compiler
     * guessing whether the flat steps join a group or ride an unweighted leg —
     * guessing is exactly what the fail-closed family forbids.
     */
    @Test
    void partialMixDeclarationRejectedNamingStep() {
        List<Map<String, Object>> partial = List.of(
                Map.of("action", "http.request", "method", "POST", "url", "{{ENV:BASE}}/orders",
                        "transaction", "下单链路", "weight", 80),
                Map.of("action", "http.request", "method", "GET", "url", "{{ENV:BASE}}/search"));
        assertThatThrownBy(() -> perfScenarioService.create(projectId,
                new PerfScenarioInput("配比守卫_半声明", envId, "CONSTANT_RPS",
                        Map.of("targetRps", 10, "durationSeconds", 60), null, null, partial, null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("第 2 步");
    }

    /**
     * Two steps in the same transaction declaring different weights contradict
     * (one link, two ratios — the compiler cannot serve both); rejected naming
     * the group and both numbers.
     */
    @Test
    void conflictingWeightInsideSameTransactionRejected() {
        List<Map<String, Object>> conflict = List.of(
                Map.of("action", "http.request", "method", "POST", "url", "{{ENV:BASE}}/a",
                        "transaction", "下单链路", "weight", 80),
                Map.of("action", "http.request", "method", "GET", "url", "{{ENV:BASE}}/b",
                        "transaction", "下单链路", "weight", 30));
        assertThatThrownBy(() -> perfScenarioService.create(projectId,
                new PerfScenarioInput("配比守卫_组内冲突", envId, "CONSTANT_RPS",
                        Map.of("targetRps", 10, "durationSeconds", 60), null, null, conflict, null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("下单链路");
    }

    /** A non-positive or non-numeric weight fails closed naming the value. */
    @Test
    void nonPositiveWeightRejectedNamingValue() {
        List<Map<String, Object>> zero = List.of(Map.of(
                "action", "http.request", "method", "POST", "url", "{{ENV:BASE}}/orders",
                "transaction", "下单链路", "weight", 0));
        assertThatThrownBy(() -> perfScenarioService.create(projectId,
                new PerfScenarioInput("配比守卫_零权重", envId, "CONSTANT_RPS",
                        Map.of("targetRps", 10, "durationSeconds", 60), null, null, zero, null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("weight");
    }

    /**
     * An orphan {@code weight} (no transaction group carrying it) fails the
     * same guard: a ratio with no group can never be compiled, and letting it
     * ride would store a promise the settings face cannot keep.
     */
    @Test
    void orphanWeightWithoutTransactionRejectedAtSave() {
        List<Map<String, Object>> orphan = List.of(Map.of(
                "action", "http.request", "method", "GET", "url", "{{ENV:BASE}}/search",
                "weight", 15));
        assertThatThrownBy(() -> perfScenarioService.create(projectId,
                new PerfScenarioInput("配比守卫_孤权重", envId, "CONSTANT_RPS",
                        Map.of("targetRps", 10, "durationSeconds", 60), null, null, orphan, null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("weight");
    }


    @Test
    void perSessionNExplicitDeclarationAcceptedAndInvalidRejected() {
        long id = perfScenarioService.create(projectId, new PerfScenarioInput("共享会话", envId, "SPIKE",
                Map.of("baseRps", 5, "peakRps", 100, "durationSeconds", 120),
                "PER_SESSION_5", null, requestSteps(), null));
        assertThat(perfScenarioService.view(projectId, id).get("login_strategy")).isEqualTo("PER_SESSION_5");
        assertThatThrownBy(() -> perfScenarioService.create(projectId,
                new PerfScenarioInput("乱策略", envId, "CONSTANT_RPS",
                        Map.of("targetRps", 5, "durationSeconds", 60), "PER_USER", null, requestSteps(), null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("PER_VUSER");
    }


    @Test
    void foreignEnvRejectedAsNotFoundWithoutLeak() {
        long thiefPid = com.sixbean.testpike.core.support.FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project (name, code, status) VALUES ('越权项目', ?, 1)",
                projectCode + "X");
        assertThatThrownBy(() -> perfScenarioService.create(thiefPid,
                new PerfScenarioInput("越权", envId, "CONSTANT_RPS",
                        Map.of("targetRps", 5, "durationSeconds", 60), null, null, requestSteps(), null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不存在");
    }


    @Test
    void engineAdapterFourStageContractFrozen() {
        List<String> names = java.util.Arrays.stream(EngineAdapter.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).sorted().toList();
        assertThat(names).containsExactlyInAnyOrder("attribute", "collect", "compile", "launch");
        List<String> components = java.util.Arrays.stream(PerfMetricFrame.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(components).contains("ttftMs", "itlMs", "tokensPerSec", "p95Ms", "errorRate");
    }


    @Test
    void perfLeaseTtlKeyDefaults1800AndHotUpdates() {
        configService.delete(1L, configId("lease.perf-ttl-seconds"));
        assertThat(perfConfigReader.perfLeaseTtlSeconds()).isEqualTo(1800);
        configService.create(1L, new ConfigInput("lease.perf-ttl-seconds", "3600", "系统", "int", 0,
                "perf 档执行器租约 TTL 秒（V137 种子）"));
        assertThat(perfConfigReader.perfLeaseTtlSeconds()).isEqualTo(3600);
        restoreSeed("lease.perf-ttl-seconds", "1800");
    }

    private Long configId(String key) {
        return jdbcTemplate.query("SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, key);
    }

    private void restoreSeed(String key, String value) {
        Long id = configId(key);
        if (id != null) {
            configService.delete(1L, id);
        }
        configService.create(1L, new ConfigInput(key, value, "系统", "int", 0,
                "perf 档执行器租约 TTL 秒（V137 种子）"));
    }


    @Test
    void scenarioSlaJsonSavesReadsBackAndNullMeansNoGate() {
        long id = perfScenarioService.create(projectId, new PerfScenarioInput("带阈值场景", envId,
                "CONSTANT_RPS", Map.of("targetRps", 10, "durationSeconds", 60), null, null,
                requestSteps(), Map.of("p95Ms", 300.0, "errorRate", 0.02, "minRps", 40.0,
                        "breakerErrorRate", 0.08)));
        Map<String, Object> view = perfScenarioService.view(projectId, id);
        assertThat((String) view.get("sla_json"))
                .contains("p95Ms").contains("breakerErrorRate");
        long bare = perfScenarioService.create(projectId, new PerfScenarioInput("无阈值场景", envId,
                "CONSTANT_RPS", Map.of("targetRps", 10, "durationSeconds", 60), null, null,
                requestSteps(), null));
        assertThat(perfScenarioService.view(projectId, bare).get("sla_json")).isNull();
    }

    @Test
    void unknownSlaKeyRejectedNamingAllowedKeys() {
        assertThatThrownBy(() -> perfScenarioService.create(projectId, new PerfScenarioInput(
                "乱键", envId, "CONSTANT_RPS", Map.of("targetRps", 5, "durationSeconds", 60),
                null, null, requestSteps(), Map.of("maxLatency", 1.0))))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("maxLatency")
                .hasMessageContaining("p95Ms");
    }

    @Test
    void negativeOrOverRangeSlaValuesRejectedInPlainWords() {
        assertThatThrownBy(() -> perfScenarioService.create(projectId, new PerfScenarioInput(
                "负值", envId, "CONSTANT_RPS", Map.of("targetRps", 5, "durationSeconds", 60),
                null, null, requestSteps(), Map.of("p95Ms", -1.0))))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("p95Ms");
        assertThatThrownBy(() -> perfScenarioService.create(projectId, new PerfScenarioInput(
                "超率", envId, "CONSTANT_RPS", Map.of("targetRps", 5, "durationSeconds", 60),
                null, null, requestSteps(), Map.of("breakerErrorRate", 5.0))))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("breakerErrorRate");
    }

    @Test
    void breakerErrorRateKeyDefaultsAndHotUpdates() {
        configService.delete(1L, configId("perf.breaker-error-rate"));
        assertThat(perfConfigReader.breakerErrorRate()).isEqualTo(0.05);
        configService.create(1L, new ConfigInput("perf.breaker-error-rate", "0.1", "系统", "string", 0,
                "压测熔断错误率阈值（V139 种子，比率 0-1）"));
        assertThat(perfConfigReader.breakerErrorRate()).isEqualTo(0.1);
        restoreStringSeed("perf.breaker-error-rate", "0.05");
    }

    @Test
    void launchSnapshotCarriesEffectiveSlaScenarioOverrideThenK8Key() throws Exception {
        // GA-2 keeps at most one in-flight load per environment; both snapshot
        // shots below share the single project test env, separated by a release.
        // No scenario thresholds: the three gates stay absent (skipped judgements)
        // and the breaker falls back to the effective K8 key value.
        long bare = perfScenarioService.create(projectId, new PerfScenarioInput("快照裸场景", envId,
                "CONSTANT_RPS", Map.of("targetRps", 10, "durationSeconds", 60), null, null,
                requestSteps(), null));
        long runId = perfRunService.launch(projectId, bare, null);
        Map<String, Object> snapshot = readJson(runView(runId).get("pressure_snapshot"));
        Map<String, Object> sla = castMap(snapshot.get("sla"));
        assertThat(sla).doesNotContainKey("p95Ms").doesNotContainKey("errorRate");
        assertThat(((Number) sla.get("breakerErrorRate")).doubleValue()).isEqualTo(0.05);

        // Scenario override wins: slaJson gates land in the snapshot verbatim and the
        // breaker takes the scenario value instead of the K8 key. GA-2 holds one
        // in-flight load per environment and the project keeps one env per type
        // (uq_project_env_type), so the second shot releases the first: the run is
        // moved to its terminal status after the snapshot assertions, which clears
        // the lock predicate (status=1 active) the way a finished run would.
        jdbcTemplate.update("UPDATE tp_perf_run SET status = 2 WHERE id = ?", runId);
        long over = perfScenarioService.create(projectId, new PerfScenarioInput("快照覆写场景", envId,
                "CONSTANT_RPS", Map.of("targetRps", 10, "durationSeconds", 60), null, null,
                requestSteps(), Map.of("p95Ms", 300.0, "breakerErrorRate", 0.12)));
        long runId2 = perfRunService.launch(projectId, over, null);
        Map<String, Object> sla2 = castMap(readJson(runView(runId2).get("pressure_snapshot")).get("sla"));
        assertThat(((Number) sla2.get("p95Ms")).doubleValue()).isEqualTo(300.0);
        assertThat(((Number) sla2.get("breakerErrorRate")).doubleValue()).isEqualTo(0.12);
    }

    private Map<String, Object> runView(long runId) {
        return jdbcTemplate.queryForMap("SELECT pressure_snapshot::text AS pressure_snapshot "
                + "FROM tp_perf_run WHERE id = ?", runId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(Object raw) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(String.valueOf(raw), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object raw) {
        return (Map<String, Object>) raw;
    }

    private void restoreStringSeed(String key, String value) {
        Long id = configId(key);
        if (id != null) {
            configService.delete(1L, id);
        }
        configService.create(1L, new ConfigInput(key, value, "系统", "string", 0,
                "压测熔断错误率阈值（V139 种子，比率 0-1）"));
    }

    // ---------- paged + whitelisted-sort scenario window ----------

    /** Create one CONSTANT_RPS scenario with a pinned driver_limit (sortable column). */
    private long seedScenario(String name, Integer driverLimit) {
        return perfScenarioService.create(projectId, new PerfScenarioInput(name, envId,
                "CONSTANT_RPS", Map.of("targetRps", 10, "durationSeconds", 60), null,
                driverLimit, requestSteps(), null));
    }

    @Test
    void pagedWindowKeepsHistoricalNewestFirstAndEchoesTruthfulTotal() {
        long first = seedScenario("alpha-scenario", 100);
        long second = seedScenario("zeta-scenario", 500);
        var window = perfScenarioService.listPaged(projectId, 1, 1, null, null);
        assertThat(window.total()).as("total counts every scenario of the project").isEqualTo(2);
        assertThat(window.list()).as("the window carries only the page").hasSize(1);
        assertThat(window.page()).isEqualTo(1);
        assertThat(window.pageSize()).isEqualTo(1);
        assertThat((Number) window.list().get(0).get("id")).as("no-sort keeps the historical id DESC order").isEqualTo(second);
        var page2 = perfScenarioService.listPaged(projectId, 2, 1, null, null);
        assertThat((Number) page2.list().get(0).get("id")).as("page 2 continues the same order").isEqualTo(first);
    }

    @Test
    void whitelistedSortKeysOrderInSqlWithIdTieBreak() {
        seedScenario("alpha-scenario", 100);
        seedScenario("zeta-scenario", 500);
        // numeric column: driver_limit asc puts the 100-cap scenario first
        var byLimit = perfScenarioService.listPaged(projectId, 1, 10, "driver_limit", "asc");
        assertThat((String) byLimit.list().get(0).get("name")).isEqualTo("alpha-scenario");
        // text column desc
        var byName = perfScenarioService.listPaged(projectId, 1, 10, "name", "desc");
        assertThat((String) byName.list().get(0).get("name")).isEqualTo("zeta-scenario");
        // every whitelist key is accepted (no throw) over the live chain
        for (String key : List.of("name", "pressure_model", "login_strategy", "driver_limit",
                "created_at", "updated_at")) {
            perfScenarioService.listPaged(projectId, 1, 10, key, "asc");
        }
    }

    @Test
    void offListOrInjectionSortKeyIsRefusedFailClosed() {
        seedScenario("alpha-scenario", 100);
        // jsonb payload columns never sortable + injection shape: all refused by name
        for (String bad : List.of("steps_json", "pressure_config", "id DESC; DROP TABLE tp_perf_scenario")) {
            assertThatThrownBy(() -> perfScenarioService.listPaged(projectId, 1, 10, bad, "asc"))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("白名单");
        }
        assertThat(perfScenarioService.list(projectId)).as("refusals touch no rows").hasSize(1);
    }

    @Test
    void pagedWindowClampsNonsenseAndAnswersEmptyProjectWithZero() {
        var window = perfScenarioService.listPaged(projectId, 0, 99999, null, null);
        assertThat(window.page()).as("page below 1 clamps to 1").isEqualTo(1);
        assertThat(window.pageSize()).as("oversized window clamps to the shared cap").isEqualTo(500);
        assertThat(window.total()).as("empty project answers a truthful zero, not an error").isZero();
        var ghost = perfScenarioService.listPaged(999_999_999L, 1, 20, null, null);
        assertThat(ghost.total()).isZero();
    }

    // ---------- dataset binding: scenario ← CSV dataset + row allocation ----------

    /** Insert a dataset ledger row directly (bytes are not read by the binding guard;
        the ledger columns the scenario validation cares about are project ownership). */
    private long seedDataset(long project) {
        return com.sixbean.testpike.core.support.FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_perf_dataset (project_id, name, file_name, object_key, "
                        + "columns_json, row_count, size_bytes) VALUES (?, '绑定池' || nextval('tp_perf_dataset_id_seq'), "
                        + "'pool.csv', 'perf-dataset/x.csv', '[\"user\"]'::jsonb, 3, 10)",
                project);
    }

    @Test
    void datasetBindingSavesReadsBackWithAllocationMode() {
        long datasetId = seedDataset(projectId);
        long id = perfScenarioService.create(projectId, new PerfScenarioInput(
                "绑池场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                requestSteps(), null, null, datasetId, "SEQUENTIAL_EXCLUSIVE"));
        Map<String, Object> view = perfScenarioService.view(projectId, id);
        assertThat(((Number) view.get("dataset_id")).longValue()).isEqualTo(datasetId);
        assertThat(view.get("row_alloc_mode")).isEqualTo("SEQUENTIAL_EXCLUSIVE");
    }

    @Test
    void unboundScenarioKeepsNullDatasetAndNullAllocation() {
        long id = perfScenarioService.create(projectId, new PerfScenarioInput(
                "无池场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                requestSteps(), null));
        Map<String, Object> view = perfScenarioService.view(projectId, id);
        assertThat(view.get("dataset_id")).isNull();
        assertThat(view.get("row_alloc_mode")).isNull();
    }

    @Test
    void allocationModeAllowedOnlyWhenDatasetBoundAndUnknownSpellingRefusedByName() {
        // allocation without a pool = nonsense declaration, refused in plain words
        assertThatThrownBy(() -> perfScenarioService.create(projectId, new PerfScenarioInput(
                "无池声明分配", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                requestSteps(), null, null, null, "CYCLE")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("数据集");
        // unknown spelling names the offender and the allowed set
        long datasetId = seedDataset(projectId);
        assertThatThrownBy(() -> perfScenarioService.create(projectId, new PerfScenarioInput(
                "乱分配", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                requestSteps(), null, null, datasetId, "RANDOM")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("RANDOM");
    }

    @Test
    void foreignProjectDatasetRejectedAsNotFoundWithoutLeak() {
        // tp_project.id is GENERATED ALWAYS — insert with the sequence default,
        // and read the new id back through RETURNING.
        String foreignCode = "PERFSCNX" + (System.nanoTime() % 100000);
        long otherProject = com.sixbean.testpike.core.support.FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project (name, code, status) VALUES ('外项目', ?, 1)", foreignCode);
        long foreignDataset = seedDataset(otherProject);
        assertThatThrownBy(() -> perfScenarioService.create(projectId, new PerfScenarioInput(
                "越权池", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                requestSteps(), null, null, foreignDataset, null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不存在");
        jdbcTemplate.update("DELETE FROM tp_perf_dataset WHERE project_id = ?", otherProject);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", otherProject);
    }

    @Test
    void updateClearsDatasetBindingOnFullOverwrite() {
        long datasetId = seedDataset(projectId);
        long id = perfScenarioService.create(projectId, new PerfScenarioInput(
                "先绑后解", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                requestSteps(), null, null, datasetId, "CYCLE"));
        perfScenarioService.update(projectId, id, new PerfScenarioInput(
                "先绑后解", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                requestSteps(), null, null, null, null));
        Map<String, Object> view = perfScenarioService.view(projectId, id);
        assertThat(view.get("dataset_id")).isNull();
        assertThat(view.get("row_alloc_mode")).isNull();
    }

    @Test
    void datasetK8DefaultKeyExistsAndReaderFallsBackToCycle() {
        // K8 seed row landed with migration V274 (hot-updatable default allocation)
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_sys_config WHERE config_key = 'perf.dataset.row-alloc-mode'",
                Integer.class)).isEqualTo(1);
        // V274 columns landed
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'tp_perf_scenario'"
                        + " AND column_name IN ('dataset_id','row_alloc_mode')",
                Integer.class)).isEqualTo(2);
    }

    // ---------- Scheduled-firing columns on the save chain ----------

    /**
     * An env owned by a different project — the cross-project negative fixture
     * for the schedule override column (cleanup matches the PERFSCN code prefix,
     * so the foreign rows cascade out with everything else).
     */
    private long seedForeignEnv() {
        long uniq = System.nanoTime() % 1_000_000L;
        long otherProject = com.sixbean.testpike.core.support.FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "schedule_foreign_project_" + uniq, "PERFSCNX" + uniq);
        return com.sixbean.testpike.core.support.FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, 'schedule-foreign-env', 1, 'http://schedule-foreign.local/api')",
                otherProject);
    }

    @Test
    void scheduleColumnsRoundTripOnCreateAndClearOnOverwriteUpdate() {
        long id = perfScenarioService.create(projectId, new PerfScenarioInput(
                "夜间容量回归_定时", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                requestSteps(), null, null, null, null,
                "0 30 2 * * *", true, envId));
        Map<String, Object> view = perfScenarioService.view(projectId, id);
        assertThat(view.get("schedule_cron")).isEqualTo("0 30 2 * * *");
        assertThat(((Number) view.get("schedule_enabled")).intValue()).isEqualTo(1);
        assertThat(((Number) view.get("schedule_env_id")).longValue()).isEqualTo(envId);
        // Full-overwrite update: dropping the schedule clears all three columns
        // (the same overwrite semantics as datasetId/isolationMode above).
        perfScenarioService.update(projectId, id, new PerfScenarioInput(
                "夜间容量回归_定时", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                requestSteps(), null, null, null, null,
                null, false, null));
        view = perfScenarioService.view(projectId, id);
        assertThat(view.get("schedule_cron")).isNull();
        assertThat(((Number) view.get("schedule_enabled")).intValue()).isEqualTo(0);
        assertThat(view.get("schedule_env_id")).isNull();
    }

    @Test
    void malformedCronIsRejectedOnSave() {
        // Five-segment form (unix style) is not the Spring six-segment contract;
        // the shared ScheduleCronTimes gate names the expected shape in plain words.
        assertThatThrownBy(() -> perfScenarioService.create(projectId, new PerfScenarioInput(
                "非法cron场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                requestSteps(), null, null, null, null,
                "30 2 * * *", true, null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("cron");
    }

    @Test
    void enabledScheduleWithoutCronIsRejected() {
        // The scanner only fires rows with a pinned cron; an enabled switch with
        // no expression would be a control that looks on but never acts — refuse
        // at save time naming the missing piece (control-scope iron law).
        assertThatThrownBy(() -> perfScenarioService.create(projectId, new PerfScenarioInput(
                "启用无cron场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                requestSteps(), null, null, null, null,
                null, true, null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("cron");
    }

    @Test
    void scheduleEnvOutsideProjectIsRejected() {
        long foreignEnv = seedForeignEnv();
        // Same wording posture as the default-env door: not-found, no leak.
        assertThatThrownBy(() -> perfScenarioService.create(projectId, new PerfScenarioInput(
                "跨项目定时环境场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                requestSteps(), null, null, null, null,
                "0 30 2 * * *", true, foreignEnv)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不属于该项目");
    }

    // ---------- B-9 custom-script mount: source/engine/script columns + save chain ----------

    /** Clean k6 body: only engine-native http DSL, nothing that escapes the sandbox. */
    private static final String CLEAN_K6_SCRIPT =
            "import http from 'k6/http';\nexport default function () { http.get('https://t.local/x'); }";

    @Test
    void customScriptScenarioSavesWithEngineAndReadsBackBody() {
        long id = perfScenarioService.create(projectId, new PerfScenarioInput(
                "自定义脚本压测", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                null, null, null, null, null, null, null, null,
                "CUSTOM", CLEAN_K6_SCRIPT, "K6"));
        Map<String, Object> view = perfScenarioService.view(projectId, id);
        assertThat(view.get("source")).isEqualTo("CUSTOM");
        assertThat(view.get("engine")).isEqualTo("K6");
        assertThat((String) view.get("custom_script_key")).isNotBlank();
        // The body itself lives in object storage; the view reads it back for the editor.
        assertThat(String.valueOf(view.get("script_content"))).contains("k6/http");
    }

    @Test
    void customScriptWithoutEngineIsRefusedNamingTheEngineSet() {
        assertThatThrownBy(() -> perfScenarioService.create(projectId, new PerfScenarioInput(
                "缺引擎自定义场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                null, null, null, null, null, null, null, null,
                "CUSTOM", CLEAN_K6_SCRIPT, null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("引擎");
    }

    @Test
    void customScriptEscapingTheSandboxIsRefusedWithScanLineNaming() {
        assertThatThrownBy(() -> perfScenarioService.create(projectId, new PerfScenarioInput(
                "越权脚本场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                null, null, null, null, null, null, null, null,
                "CUSTOM", "import fs from 'fs';\n" + CLEAN_K6_SCRIPT, "K6")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("宿主内置模块");
    }

    @Test
    void customScriptWithoutBodyIsRefused() {
        assertThatThrownBy(() -> perfScenarioService.create(projectId, new PerfScenarioInput(
                "空脚本场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                null, null, null, null, null, null, null, null,
                "CUSTOM", "  ", "K6")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("脚本");
    }

    @Test
    void dslScenarioCarryingScriptIsRefusedAsMutuallyExclusive() {
        assertThatThrownBy(() -> perfScenarioService.create(projectId, new PerfScenarioInput(
                "DSL带脚本场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                requestSteps(), null, null, null, null, null, null, null,
                "DSL", CLEAN_K6_SCRIPT, "K6")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不能同时");
    }

    @Test
    void customScriptScenarioStillCannotCarryDslSteps() {
        // One scenario declares one thing-to-run: a custom script and DSL steps
        // together would leave the launch ambiguous about which one gets pressed.
        assertThatThrownBy(() -> perfScenarioService.create(projectId, new PerfScenarioInput(
                "脚本加步骤场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                requestSteps(), null, null, null, null, null, null, null,
                "CUSTOM", CLEAN_K6_SCRIPT, "K6")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("自定义脚本");
    }

    @Test
    void unknownSourceSpellingIsRefusedByName() {
        assertThatThrownBy(() -> perfScenarioService.create(projectId, new PerfScenarioInput(
                "非法来源场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                requestSteps(), null, null, null, null, null, null, null,
                "MIXED", null, null)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("DSL");
    }

    // ---------- migration proof: tables exist on the dev database ----------

    @Test
    void scenarioAndRunTablesExist() {
        for (String table : List.of("tp_perf_scenario", "tp_perf_run")) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
                    Integer.class, table)).as("表 %s 应在 dev 库在位", table).isEqualTo(1);
        }
    }

    // ---------- A-13: scenario deletion keeps finished runs (D2 decoupling) ----------

    @Test
    void deleteRefusesWhileARunIsInFlight() {
        long scenarioId = perfScenarioService.create(projectId, new PerfScenarioInput(
                "在跑拒删场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 10, "durationSeconds", 60), null, null,
                requestSteps(), null));
        long runId = perfRunService.launch(projectId, scenarioId, null);
        assertThatThrownBy(() -> perfScenarioService.delete(projectId, scenarioId))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("运行中");
        // The refusal must not half-delete anything.
        assertThat(perfScenarioService.view(projectId, scenarioId)).isNotNull();
        jdbcTemplate.update("UPDATE tp_perf_run SET status = 2 WHERE id = ?", runId);
    }

    @Test
    void deleteKeepsFinishedRunsDecoupledAndSnapshotIntact() {
        long scenarioId = perfScenarioService.create(projectId, new PerfScenarioInput(
                "删后保史场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 10, "durationSeconds", 60), null, null,
                requestSteps(), null));
        long runId = perfRunService.launch(projectId, scenarioId, null);
        jdbcTemplate.update("UPDATE tp_perf_run SET status = 2 WHERE id = ?", runId);
        perfScenarioService.delete(projectId, scenarioId);
        // D2: the finished run survives with a NULL link; the pinned snapshot keeps
        // the scenario name so report faces still read the truth without the row.
        Map<String, Object> run = jdbcTemplate.queryForMap(
                "SELECT scenario_id, pressure_snapshot::text AS snap FROM tp_perf_run WHERE id = ?",
                runId);
        assertThat(run.get("scenario_id")).isNull();
        assertThat((String) run.get("snap")).contains("删后保史场景");
        assertThatThrownBy(() -> perfScenarioService.view(projectId, scenarioId))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void deleteUnlinksPendingApprovalTicket() {
        long scenarioId = perfScenarioService.create(projectId, new PerfScenarioInput(
                "审批摘链场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 10, "durationSeconds", 60), null, null,
                requestSteps(), null));
        Long applicant = jdbcTemplate.queryForObject("SELECT min(id) FROM tp_user", Long.class);
        Long approvalId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_perf_approval (project_id, env_id, requested_concurrency, "
                        + "isolation_mode, reason, applicant_id, scenario_id) "
                        + "VALUES (?, ?, 10, 'HEADER_MARK', 'A-13 夹具审批单', ?, ?) RETURNING id",
                Long.class, projectId, envId, applicant, scenarioId);
        perfScenarioService.delete(projectId, scenarioId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT scenario_id FROM tp_perf_approval WHERE id = ?", Long.class, approvalId))
                .isNull();
    }

    @Test
    void deleteImpactReportsTheLedgerBeforeRemoving() {
        long scenarioId = perfScenarioService.create(projectId, new PerfScenarioInput(
                "影响面账目场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 10, "durationSeconds", 60), null, null,
                requestSteps(), null));
        long runId = perfRunService.launch(projectId, scenarioId, null);
        Long applicant = jdbcTemplate.queryForObject("SELECT min(id) FROM tp_user", Long.class);
        jdbcTemplate.update(
                "INSERT INTO tp_perf_approval (project_id, env_id, requested_concurrency, "
                        + "isolation_mode, reason, applicant_id, scenario_id) "
                        + "VALUES (?, ?, 10, 'HEADER_MARK', 'A-13 影响面夹具', ?, ?)",
                projectId, envId, applicant, scenarioId);
        Map<String, Object> impact = perfScenarioService.deleteImpact(projectId, scenarioId);
        assertThat(((Number) impact.get("runningRuns")).intValue()).isEqualTo(1);
        assertThat(((Number) impact.get("pendingApprovals")).intValue()).isEqualTo(1);
        assertThat(impact.get("source")).isEqualTo("DSL");
        jdbcTemplate.update("UPDATE tp_perf_run SET status = 2 WHERE id = ?", runId);
        Map<String, Object> after = perfScenarioService.deleteImpact(projectId, scenarioId);
        assertThat(((Number) after.get("historicalRuns")).intValue()).isEqualTo(1);
        assertThat(((Number) after.get("runningRuns")).intValue()).isZero();
    }

    @Test
    void deleteOfForeignOrUnknownScenarioIsRefusedWithoutLeak() {
        assertThatThrownBy(() -> perfScenarioService.delete(projectId, 999_999_999L))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不存在");
    }

    // ---------- W-d engine binding on the DSL face: explicit engine per scenario ----------

    @Test
    void dslScenarioWithEngineBindingReadsBackNormalizedEngine() {
        long id = perfScenarioService.create(projectId, new PerfScenarioInput(
                "引擎绑定DSL场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                requestSteps(), null, null, null, null, null, null, null,
                "DSL", null, "jmeter"));
        assertThat(perfScenarioService.view(projectId, id).get("engine")).isEqualTo("JMETER");
    }

    @Test
    void dslScenarioEngineOutsideValueDomainRejectedNamingValues() {
        assertThatThrownBy(() -> perfScenarioService.create(projectId, new PerfScenarioInput(
                "非法引擎DSL场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                requestSteps(), null, null, null, null, null, null, null,
                "DSL", null, "BOOM")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("BOOM")
                .hasMessageContaining("JMETER");
    }

    @Test
    void dslScenarioWithoutEngineBindingReadsBackNullEngine() {
        long id = perfScenarioService.create(projectId, new PerfScenarioInput(
                "留空引擎DSL场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                requestSteps(), null, null, null, null, null, null, null,
                "DSL", null, null));
        assertThat(perfScenarioService.view(projectId, id).get("engine")).isNull();
    }

    @Test
    void customJmeterScriptSavesWithJmeterMountName() {
        String cleanJmx = """
                <?xml version="1.0" encoding="UTF-8"?>
                <jmeterTestPlan version="1.2">
                  <hashTree>
                    <TestPlan guient-class="TestPlan"><stringProp name="TestPlan.comments">perf</stringProp></TestPlan>
                    <hashTree>
                      <ThreadGroup guient-class="ThreadGroup"/>
                      <hashTree>
                        <HTTPSamplerProxy guient-class="HTTPSamplerProxy">
                          <stringProp name="HTTPSampler.path">/api/orders</stringProp>
                        </HTTPSamplerProxy>
                      </hashTree>
                    </hashTree>
                  </hashTree>
                </jmeterTestPlan>
                """;
        long id = perfScenarioService.create(projectId, new PerfScenarioInput(
                "自定义JMeter脚本场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                null, null, null, null, null, null, null, null,
                "CUSTOM", cleanJmx, "JMETER"));
        Map<String, Object> view = perfScenarioService.view(projectId, id);
        assertThat(view.get("engine")).isEqualTo("JMETER");
        assertThat((String) view.get("custom_script_key")).contains("scenario.jmx");
    }

    @Test
    void customJmeterScriptEscapingTheRunnerIsRefusedWithScanLineNaming() {
        String unsafeJmx = """
                <?xml version="1.0" encoding="UTF-8"?>
                <jmeterTestPlan version="1.2">
                  <BeanShellPreProcessor guient-class="BeanShellPreProcessor">
                    <stringProp name="filename">Runtime.getRuntime().exec("id")</stringProp>
                  </BeanShellPreProcessor>
                </jmeterTestPlan>
                """;
        assertThatThrownBy(() -> perfScenarioService.create(projectId, new PerfScenarioInput(
                "越权JMeter脚本场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                null, null, null, null, null, null, null, null,
                "CUSTOM", unsafeJmx, "JMETER")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("第 3 行");
    }
}
