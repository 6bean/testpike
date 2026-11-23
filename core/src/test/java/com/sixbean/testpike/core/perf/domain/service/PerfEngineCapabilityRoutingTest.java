package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.service.PerfRunService;
import com.sixbean.testpike.core.perf.domain.service.PerfScenarioService;
import com.sixbean.testpike.core.support.FleetViewQuiescer;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Load-engine capability routing (GA-4 consumer side on core): perf-run
 * candidates must only reach executors whose self-probed capability face
 * carries the envelope engine, and an order-time early rejection must name
 * the missing engine instead of silently queueing forever when an online
 * fleet exists but nobody in it can start the engine.
 * <p>Claim side mirrors the app-auto three-dimension routing posture
 * (candidate-level skip, never an exception — the pool/sticky gates shape).
 * Dispatch side keeps one honest difference: an EMPTY online fleet is not
 * a rejection (the existing queue-note contract says "the run auto-starts
 * once an executor node comes online"); only "online N, capable 0" rejects.
 * <p>Real chain on the dev DB, strict-testing posture: no mocks. Teardown
 * is fixture-scoped : the PERFROUTE project family is swept
 * through the FK tree and the 'route-ex-%' executor family by name — the old
 * blanket TRUNCATE deleted rows owned by concurrent suites. The routing
 * assertions that watch the online view run inside a {@link FleetViewQuiescer}
 * method window so the persistent runner cannot claim fixtures mid-assertion.
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfEngineCapabilityRoutingTest {

    private static final String K6_ENVELOPE_FMT =
            "{\"kind\":\"perf-run\",\"runId\":%d,\"engine\":\"K6\","
                    + "\"artifact\":\"/tmp/perf-route-%d.js\"}";

    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private com.sixbean.testpike.core.executor.domain.service.ExecutorService executorService;
    @Autowired
    private PerfScenarioService perfScenarioService;
    @Autowired
    private PerfRunService perfRunService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;

    @BeforeEach
    @AfterEach
    void cleanupRows() {
        // Fixture-scoped teardown :
        // sweep this suite's PERFROUTE project family with its FK tree (tp_exec_task
        // and the tp_perf_* tables are reached from tp_project) plus its own
        // 'route-ex-%' executor rows. Never TRUNCATE shared dispatch tables.
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, jdbcTemplate.queryForList(
                "SELECT id FROM tp_project WHERE code LIKE 'PERFROUTE%'", Long.class));
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'route-ex-%'");
    }

    @BeforeEach
    void seedFixtures() {
        long[] ids = SharedDbFixtureSweep.seedPerfFixtureProject(jdbcTemplate,
                "压测能力路由测试项目_", "PERFROUTE",
                "能力-路由测", "http://perf-route-target.local/api");
        projectId = ids[0];
        envId = ids[1];
    }

    private long newRunRow() {
        long scenarioId = perfScenarioService.create(projectId, new PerfScenarioInput(
                "能力路由场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 40, "durationSeconds", 60), null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-route-target.local/api/orders")), null));
        // Insert the run row directly: launching would auto-dispatch through
        // PerfRunDispatcher and fight the explicit-envelope control this suite needs.
        return jdbcTemplate.queryForObject("INSERT INTO tp_perf_run (scenario_id, project_id,"
                + " pressure_snapshot) VALUES (?, ?, '{}'::jsonb) RETURNING id",
                Long.class, scenarioId, projectId);
    }

    private long registerWithCaps(String name, Map<String, Object> caps) {
        return executorService.register(new ExecutorRegisterInput(
                name, null, null, "web,perf", "chromium", 2, "1.0.0", 0, caps));
    }

    /**
     * Method-scoped fleet window: age every online executor outside the
     * 'route-ex-%' family out of the recomputed online view for the duration
     * of the body, then restore (FleetViewQuiescer semantics note: strictly
     * lighter than the TRUNCATE it replaces; runner heartbeat self-heals).
     */
    private void withQuiescedFleet(Runnable body) {
        List<Long> aged = FleetViewQuiescer.quiesceOthers(jdbcTemplate, "route-ex-%");
        try {
            body.run();
        } finally {
            FleetViewQuiescer.restore(jdbcTemplate, aged);
        }
    }

    // ── ① claim routing: engine face decides candidate reach ──────────────

    /** RED anchor 1: an online executor without the K6 engine face must not be
     *  able to claim a K6 perf candidate; the task stays pending (queued, not failed). */
    @Test
    void claim_skips_perf_candidate_when_executor_lacks_engine_face() {
        withQuiescedFleet(() -> {
            long runId = newRunRow();
            long taskId = execTaskService.createPerfRunTask(projectId,
                    String.format(K6_ENVELOPE_FMT, runId, runId));
            long exId = registerWithCaps("route-ex-no-k6", Map.of(
                    "engines", List.of("MOBILEWRIGHT", "MAESTRO")));

            assertNull(execTaskService.claim(exId),
                    "无 K6 能力面的执行器对 perf 候选必须跳过（领不到，禁异常）");
            Integer status = jdbcTemplate.queryForObject(
                    "SELECT status FROM tp_exec_task WHERE id = ?", Integer.class, taskId);
            assertEquals(1, status, "被跳过的压测任务必须滞留待派发(1)=排队非失败");
        });
    }

    /** Positive face: the engine code present on the capability face claims as before. */
    @Test
    void claim_dispatches_perf_candidate_when_executor_reports_engine() {
        withQuiescedFleet(() -> {
            long runId = newRunRow();
            long taskId = execTaskService.createPerfRunTask(projectId,
                    String.format(K6_ENVELOPE_FMT, runId, runId));
            long exId = registerWithCaps("route-ex-k6", Map.of("engines", List.of("K6")));

            ClaimTaskView view = execTaskService.claim(exId);
            assertNotNull(view, "具备 K6 能力面的执行器必须能领压测候选");
            assertEquals(taskId, view.taskId());
            assertEquals(5, view.taskType());
            assertNotNull(view.perf(), "perf 执行上下文尾追不受路由判定影响");
        });
    }

    /** Executors that never reported a capability face keep the honest-absence
     *  posture of the app-auto side: unknown capability = perf dispatch refused. */
    @Test
    void claim_skips_perf_candidate_for_executor_without_capabilities_report() {
        withQuiescedFleet(() -> {
            long runId = newRunRow();
            execTaskService.createPerfRunTask(projectId,
                    String.format(K6_ENVELOPE_FMT, runId, runId));
            long exId = executorService.register(new ExecutorRegisterInput(
                    "route-ex-blind", null, null, "web,perf", "chromium", 2, "0.9.0", 0));

            assertNull(execTaskService.claim(exId),
                    "未上报能力面=能力未知，压测任务禁派发（谎报红线同 APP 侧）");
        });
    }

    /** Zero-drift guard: non-perf candidates are untouched by perf routing —
     *  a browser task must still reach a perf-incapable executor. */
    @Test
    void claim_still_dispatches_non_perf_task_through_perf_incapable_executor() {
        withQuiescedFleet(() -> {
            long taskId = jdbcTemplate.queryForObject(
                    "INSERT INTO tp_exec_task (project_id, case_id, status, schema_version,"
                            + " action_json, task_type) VALUES (?, 0, 1, 'v2',"
                            + " '{\"version\":\"v2\",\"steps\":[]}', 1) RETURNING id",
                    Long.class, projectId);
            long exId = registerWithCaps("route-ex-browser", Map.of("engines", List.of()));

            ClaimTaskView view = execTaskService.claim(exId);
            assertNotNull(view, "非压测信封恒放行=旧链路零破坏");
            assertEquals(taskId, view.taskId());
        });
    }

    // ── ② order-side early rejection: online N, capable 0 = reject with wording ──

    /** RED anchor 2: an online fleet with zero K6-capable nodes must reject the
     *  order up front (human wording names the engine and the 0 count) and the
     *  task row must never be inserted. */
    @Test
    void createPerfRunTask_rejects_when_online_fleet_has_no_capable_executor() {
        withQuiescedFleet(() -> {
            long runId = newRunRow();
            registerWithCaps("route-ex-gatling-only", Map.of("engines", List.of("GATLING")));

            ServiceException reject = assertThrows(ServiceException.class,
                    () -> execTaskService.createPerfRunTask(projectId,
                            String.format(K6_ENVELOPE_FMT, runId, runId)),
                    "在线但零台具备 K6 能力=建单侧秒拒");
            assertTrue(reject.getMessage().contains("K6"), "话术必须点名所需引擎: " + reject.getMessage());
            assertTrue(reject.getMessage().contains("0 台"), "话术必须给出可执行台数: " + reject.getMessage());
            Integer tasks = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_exec_task WHERE project_id = ? AND task_type = 5",
                    Integer.class, projectId);
            assertEquals(0, tasks, "秒拒必须零落库（任务行不出）");
        });
    }

    /** Contract preservation: an EMPTY online fleet is not a rejection — the
     *  run queues with the existing honest queue-note ("auto-starts once a node
     *  comes online"). This keeps PerfRunQueueNoteTest semantics intact. */
    @Test
    void createPerfRunTask_accepts_and_queues_when_no_executor_online_at_all() {
        withQuiescedFleet(() -> {
            long runId = newRunRow();
            long taskId = execTaskService.createPerfRunTask(projectId,
                    String.format(K6_ENVELOPE_FMT, runId, runId));
            assertTrue(taskId > 0, "零在线执行器=排队语义保留（不是能力缺失，是暂无机器）");
            Integer status = jdbcTemplate.queryForObject(
                    "SELECT status FROM tp_exec_task WHERE id = ?", Integer.class, taskId);
            assertEquals(1, status);
        });
    }

    /** Positive face of the gate: one capable node online = order accepted. */
    @Test
    void createPerfRunTask_accepts_when_a_capable_executor_is_online() {
        long runId = newRunRow();
        registerWithCaps("route-ex-k6-online", Map.of("engines", List.of("K6")));
        long taskId = execTaskService.createPerfRunTask(projectId,
                String.format(K6_ENVELOPE_FMT, runId, runId));
        assertTrue(taskId > 0);
    }
}
