package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.support.FleetViewQuiescer;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A-14 perf run budget self-healing: the dispatch envelope must carry a run-time
 * derived budget (scenario duration + hot-updatable margin), the claim path must
 * hand that budget to the executor as timeoutMs for task_type=5 only, and the
 * server-side timeout sweep must judge a perf task by its own budget instead of
 * the global exec.case-timeout-minutes (default 10 minutes).
 * <p>Bug pinned by this class (recon tick, live code): dispatch emitted no budget
 * key, claim always sent case-timeout (600000ms), sweep aged out every running
 * row after the same 10 minutes — a 60-minute soak run was cut down at minute
 * ten by both the executor watchdog and the server sweep.
 * <p>Real chain, no mocks: dev DB fixtures + service calls (posture follows
 * PerfRunLaunchDispatchTest / BatchTimeoutTest; clock aging via SQL, never via
 * clock mocks). Teardown is fixture-scoped : the PERFBUDGET
 * project family through the FK tree plus the 'ex-budget%' executor family;
 * the two claim-facing methods run inside a {@link FleetViewQuiescer} window
 * so the persistent runner cannot steal the walked perf row mid-assertion.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfRunDispatchBudgetTest {

    /** Margin key under test (K8 hot-updatable, default 10 minutes). */
    private static final String MARGIN_KEY = "perf.budget-margin-minutes";

    @Autowired
    private ExecTaskService execTaskService;

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;

    @BeforeEach
    void seedBudgetFixture() {
        cleanupBudgetRows();
        // Fresh-install CI has no runner process online with an engine face,
        // so the perf launch gate would reject every run: seed one capable seat.
        SharedDbFixtureSweep.seedCapableExecutor(jdbcTemplate, "perf-budget-seed-ex-",
                "GATLING", "K6");
        long[] ids = SharedDbFixtureSweep.seedPerfFixtureProject(jdbcTemplate,
                "预算自愈测试项目_", "PERFBUDGET",
                "性能-预算自愈", "http://perf-target.local/api");
        projectId = ids[0];
        envId = ids[1];
    }

    @AfterEach
    void cleanupBudgetFixture() {
        cleanupBudgetRows();
    }

    private void cleanupBudgetRows() {
        SharedDbFixtureSweep.executorsByNamePrefixes(jdbcTemplate, "perf-budget-seed-ex-");
        Long marginId = jdbcTemplate.query(
                "SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, MARGIN_KEY);
        if (marginId != null) {
            configService.delete(null, marginId);
        }
        configService.create(null, new ConfigInput(MARGIN_KEY, "10", "系统", "int", 0,
                "压测预算宽限分钟（A-14；信封 budgetMs=场景时长+本值，热更即生效，非法回退 10）"));
        // Fixture-scoped teardown :
        // sweep this suite's PERFBUDGET family (FK tree reaches tp_exec_task and the
        // tp_perf_* tables) plus its own executors — never TRUNCATE shared tables.
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, jdbcTemplate.queryForList(
                "SELECT id FROM tp_project WHERE code LIKE 'PERFBUDGET%'", Long.class));
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-budget%'");
    }

    private long newScenario(String model, Map<String, Integer> config) {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                "预算自愈场景_" + System.nanoTime(), envId, model, config, null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), null));
    }

    private String envelopeOf(long runId) {
        return jdbcTemplate.queryForMap("SELECT action_json FROM tp_exec_task "
                + "WHERE project_id = ? AND schema_version = 'perf-v1'", projectId)
                .get("action_json").toString();
    }

    /** Hot-update the margin through the real ConfigService chain (seed row replace pattern). */
    private void setMargin(String value) {
        Long id = jdbcTemplate.query("SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, MARGIN_KEY);
        assertNotNull(id, "margin seed row must exist for the hot-update path");
        configService.delete(null, id);
        configService.create(null, new ConfigInput(MARGIN_KEY, value, "系统", "int", 0,
                "压测预算宽限分钟（A-14；信封 budgetMs=场景时长+本值，热更即生效，非法回退 10）"));
    }

    /** Method-scoped fleet window around the claim-facing bodies (see FleetViewQuiescer). */
    private void withQuiescedFleet(Runnable body) {
        List<Long> aged = FleetViewQuiescer.quiesceOthers(jdbcTemplate, "ex-budget%");
        try {
            body.run();
        } finally {
            FleetViewQuiescer.restore(jdbcTemplate, aged);
        }
    }

    /** Envelope carries budgetMs = duration + margin (CONSTANT_RPS 60s + 10min = 660000ms). */
    @Test
    void launch_constantRps_信封携场景时长加宽限预算() {
        long scenarioId = newScenario("CONSTANT_RPS",
                Map.of("targetRps", 40, "durationSeconds", 60));

        long runId = perfRunService.launch(projectId, scenarioId, null);

        String envelope = envelopeOf(runId);
        assertTrue(envelope.contains("\"budgetMs\":660000"),
                "信封必须携 (60+10*60)*1000=660000 预算: " + envelope);
    }

    /** RAMP model budget = (rampSeconds + holdSeconds) + margin. */
    @Test
    void launch_ramp模型_预算取爬坡加保持时长() {
        long scenarioId = newScenario("RAMP",
                Map.of("startRps", 5, "peakRps", 50, "rampSeconds", 120, "holdSeconds", 180));

        long runId = perfRunService.launch(projectId, scenarioId, null);

        // (120+180+10*60)*1000 = 900000
        assertTrue(envelopeOf(runId).contains("\"budgetMs\":900000"),
                "RAMP 预算=ramp+hold+宽限: " + envelopeOf(runId));
    }

    /** Margin is hot-updatable: with margin=0 the envelope budget is the raw duration. */
    @Test
    void launch_宽限热更生效_预算随时长不含旧宽限() {
        setMargin("0");
        long scenarioId = newScenario("CONSTANT_RPS",
                Map.of("targetRps", 40, "durationSeconds", 300));

        long runId = perfRunService.launch(projectId, scenarioId, null);

        assertTrue(envelopeOf(runId).contains("\"budgetMs\":300000"),
                "宽限 0=裸时长 300s: " + envelopeOf(runId));
    }

    /** Claim hands the envelope budget to a perf task, other task types keep the global case timeout. */
    @Test
    void claim_perf任务下发自身预算_非压测任务零扰动() {
        withQuiescedFleet(() -> {
            // Negative control in the queue: a non-perf task must keep the global case
            // timeout while the perf row gets its own envelope budget. The dev DB is
            // shared across suites, so claim is walked (queue order) until our perf
            // row surfaces — every non-perf claim along the way asserts 600000.
            long scenarioId = newScenario("CONSTANT_RPS",
                    Map.of("targetRps", 40, "durationSeconds", 3600));
            perfRunService.launch(projectId, scenarioId, null);
            long executorId = executorService.register(new ExecutorRegisterInput(
                    "ex-budget", null, null, "web", "chromium", 10, "v1", 0,
                    // GA-4 onward the claim path gates perf candidates on the
                    // reported engine face — this fixture walks the queue to its
                    // own perf row, so it must report both engines.
                    java.util.Map.of("engines", java.util.List.of("K6", "GATLING"))));

            ClaimTaskView perf = null;
            for (int hop = 0; hop < 10 && perf == null; hop++) {
                ClaimTaskView view = execTaskService.claim(executorId);
                assertNotNull(view, "队列为空前必须领到本次压测任务");
                if (view.taskType() == 5) {
                    perf = view;
                } else {
                    assertEquals(600000L, view.timeoutMs(),
                            "非压测型别必须维持全局 case 键 600000ms（零扰动负向）");
                }
            }
            assertNotNull(perf, "压测任务经 claim 通道可领取");
            // (3600 + 10*60) * 1000 = 4200000
            assertEquals(4200000L, perf.timeoutMs(),
                    "压测任务 claim 下发信封 budgetMs，禁 600000 腰斩");
        });
    }

    /** Sweep judges a perf row by its own budget: not timed out while inside it, marked once beyond. */
    @Test
    void sweep_压测行按自身预算判超时_在预算内不误伤() {
        withQuiescedFleet(() -> {
            long scenarioId = newScenario("CONSTANT_RPS",
                    Map.of("targetRps", 40, "durationSeconds", 3600)); // budget = 70 minutes
            perfRunService.launch(projectId, scenarioId, null);
            long executorId = executorService.register(new ExecutorRegisterInput(
                    "ex-budget-sweep", null, null, "web", "chromium", 2, "v1", 0,
                    // Same GA-4 capability-face contract as the claim-walk fixture above.
                    java.util.Map.of("engines", java.util.List.of("K6", "GATLING"))));
            ClaimTaskView claimed = execTaskService.claim(executorId);
            assertNotNull(claimed);

            // Age past the global 10-minute threshold but still inside the 70-minute budget.
            jdbcTemplate.update("UPDATE tp_exec_task SET started_at = now() - make_interval(mins => 15)"
                    + " WHERE id = ?", claimed.taskId());
            int swept = execTaskService.sweepTimeoutTasks();
            assertEquals(0, swept, "预算内的压测行不得被全局 10 分钟线腰斩");
            assertEquals(2, taskStatus(claimed.taskId()), "在预算内保持执行中(2)");

            // Age past the run budget itself → sweep marks timeout with the budget in the message.
            jdbcTemplate.update("UPDATE tp_exec_task SET started_at = now() - make_interval(mins => 71)"
                    + " WHERE id = ?", claimed.taskId());
            swept = execTaskService.sweepTimeoutTasks();
            assertEquals(1, swept, "越自身预算的压测行必须被清扫");
            assertEquals(5, taskStatus(claimed.taskId()), "越预算后置超时(5)");
        });
    }

    @Autowired
    private com.sixbean.testpike.core.executor.domain.service.ExecutorService executorService;

    private int taskStatus(long taskId) {
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_exec_task WHERE id = ?", Integer.class, taskId);
        return status == null ? -1 : status;
    }
}
