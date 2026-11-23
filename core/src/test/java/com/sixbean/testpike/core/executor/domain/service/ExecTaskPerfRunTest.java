package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.service.PerfRunService;
import com.sixbean.testpike.core.perf.domain.service.PerfScenarioService;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.support.PerfLaunchFixture;
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


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class ExecTaskPerfRunTest {

    @Autowired
    private ExecTaskService execTaskService;

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;

    @BeforeEach
    void seedFixtures() {
        cleanupRows();
        long uniq = System.nanoTime() % 1_000_000_000L;
        // Ids come back through RETURNING: on the shared dev database a
        // concurrent chain can insert a project row between our INSERT and a
        // global max(id) lookup, binding the fixture to a foreign project
        // (the pitfall the sibling suites' teardown comments name).
        projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, "压测派发测试项目_" + uniq, "PERFCHAIN" + uniq);
        envId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '性能-派发测', 1, 'http://perf-target.local/api') RETURNING id",
                Long.class, projectId);
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, allowed_modes) VALUES (?, ?, 1000, '[]'::jsonb)", projectId, envId);
        // Dispatch-capability self-sufficiency: the launch auto-dispatch gate
        // fails closed when executors are online but none report the perf
        // engines. Register one capable executor inside this suite's own
        // 'ex-perf-' name family so the gate verdict never depends on (and
        // never deletes) rows a live runner owns on the shared database.
        executorService.register(new ExecutorRegisterInput(
                "ex-perf-dispatch-cap", null, null, "web", "chromium", 4, "v1", 0,
                java.util.Map.of("engines", java.util.List.of("K6", "GATLING"))));
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
    }

    private void cleanupRows() {
        // Fixture-scoped teardown (truncate-ratchet discipline): dispatch rows drop by this
        // suite's project-code family (the single tp_exec_task child cascade-deletes
        // with them) and executors by the ex-perf- name family. A blanket
        // TRUNCATE would wipe rows owned by concurrent suites and L3 probes on
        // the shared dev database; the perf-domain tables below drop with the
        // project-code family instead.
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFCHAIN%')");
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-perf-%'");
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFCHAIN%')");
        jdbcTemplate.update("DELETE FROM tp_perf_sample WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFCHAIN%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFCHAIN%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFCHAIN%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFCHAIN%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFCHAIN%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PERFCHAIN%'");
    }

    private long newScenarioRun() {
        // Launch, then detach the auto-dispatch row so the explicit
        // create/claim channel under test owns dispatch timing (the auto
        // chain belongs to PerfRunLaunchDispatchTest). Single source (R40):
        // PerfLaunchFixture — same shape as PerfRunStopServiceTest.
        return PerfLaunchFixture.launchDetachedRun(
                jdbcTemplate, perfScenarioService, perfRunService, projectId, envId,
                "派发契约场景", Map.of("targetRps", 40, "durationSeconds", 60));
    }

    
    @Test
    void createPerfRunTask_落任务行_哨兵与类型与版本正确() {
        long runId = newScenarioRun();
        String envelope = "{\"kind\":\"perf-run\",\"runId\":" + runId
                + ",\"engine\":\"K6\",\"artifact\":\"/tmp/perf-" + runId + ".js\"}";

        long taskId = execTaskService.createPerfRunTask(projectId, envelope);
        assertTrue(taskId > 0);

        var row = jdbcTemplate.queryForMap(
                "SELECT project_id, case_id, task_type, status, schema_version, env_base_url,"
                        + " action_json FROM tp_exec_task WHERE id = ?", taskId);
        assertEquals(projectId, ((Number) row.get("project_id")).longValue());
        assertEquals(0L, ((Number) row.get("case_id")).longValue(), "case_id=0 哨兵（零 FK 承载）");
        assertEquals(5, ((Number) row.get("task_type")).intValue(), "压测任务必须 task_type=5");
        assertEquals(1, ((Number) row.get("status")).intValue(), "落库即待派发(1)");
        assertEquals("perf-v1", row.get("schema_version"), "压测信封 schema 版本（perf-v1）");
        assertEquals(envelope, row.get("action_json"));
    }

    
    @Test
    void createPerfRunTask_非法信封拒40001_不落库() {
        assertThrows(ServiceException.class,
                () -> execTaskService.createPerfRunTask(0L, "{\"kind\":\"perf-run\"}"));
        assertThrows(ServiceException.class,
                () -> execTaskService.createPerfRunTask(projectId, "  "));

        ServiceException badKind = assertThrows(ServiceException.class,
                () -> execTaskService.createPerfRunTask(projectId,
                        "{\"kind\":\"api-test-run\",\"runId\":1,\"engine\":\"K6\"}"));
        assertTrue(badKind.getMessage().contains("perf-run"), "人话点名: " + badKind.getMessage());

        ServiceException noRun = assertThrows(ServiceException.class,
                () -> execTaskService.createPerfRunTask(projectId,
                        "{\"kind\":\"perf-run\",\"engine\":\"K6\"}"));
        assertTrue(noRun.getMessage().contains("runId"), "人话点名: " + noRun.getMessage());

        ServiceException badEngine = assertThrows(ServiceException.class,
                () -> execTaskService.createPerfRunTask(projectId,
                        "{\"kind\":\"perf-run\",\"runId\":" + newScenarioRun()
                                + ",\"engine\":\"JMeter\"}"));
        assertTrue(badEngine.getMessage().contains("引擎"), "人话点名: " + badEngine.getMessage());

        Long rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE schema_version = 'perf-v1'"
                        + " AND project_id = ?", Long.class, projectId);
        assertEquals(0L, rows);
    }

    
    @Test
    void createPerfRunTask_跨项目运行拒_不泄漏存在性() {
        long runId = newScenarioRun();
        // Foreign-project forgery: a second project pointing at the same run,
        // id captured through RETURNING (no global max(id) lookup).
        long uniq = System.nanoTime() % 1_000_000_000L;
        long foreign = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, "压测派发他项目_" + uniq, "PERFCHAINX" + uniq);

        ServiceException e = assertThrows(ServiceException.class,
                () -> execTaskService.createPerfRunTask(foreign,
                        "{\"kind\":\"perf-run\",\"runId\":" + runId + ",\"engine\":\"K6\"}"));
        assertEquals(40401, e.getCode().intValue(), "跨项目=404 人话不泄漏");
    }

    
    @Test
    void claim_压测任务可领取_尾追perf上下文携钉版快照() {
        long runId = newScenarioRun();
        long taskId = execTaskService.createPerfRunTask(projectId,
                "{\"kind\":\"perf-run\",\"runId\":" + runId
                        + ",\"engine\":\"K6\",\"artifact\":\"/tmp/perf-" + runId + ".js\"}");
        long executorId = executorService.register(new ExecutorRegisterInput(
                "ex-perf-chain", null, null, "web", "chromium", 2, "v1", 0,
                // GA-4 onward: perf candidates are gated on the capability face
                // (unknown capability = refused, same honest-absence contract as
                // app-auto) — a claim-path fixture must report envelope engines.
                java.util.Map.of("engines", java.util.List.of("K6", "GATLING"))));

        ClaimTaskView view = execTaskService.claim(executorId);

        assertNotNull(view, "压测任务必须能经 claim 通道领取（红线6 零新通道）");
        assertEquals(taskId, view.taskId());
        assertEquals(5, view.taskType(), "压测任务必须下发 taskType=5");
        assertEquals(0L, view.caseId(), "case_id=0 哨兵下发");
        assertNotNull(view.perf(), "压测任务必须尾追 perf 执行上下文");
        assertEquals(runId, ((Number) view.perf().get("runId")).longValue());
        assertEquals("K6", view.perf().get("engine"));
        assertEquals("/tmp/perf-" + runId + ".js", view.perf().get("artifact"));
        assertNotNull(view.perf().get("pressureSnapshot"),
                "领取即携启动钉版快照（T280 同纪律：执行器禁反查源表）");
        assertTrue(String.valueOf(view.perf().get("pressureSnapshot")).contains("CONSTANT_RPS"),
                "快照内容=run 行钉版 DSL");
    }

    
    @Test
    void claim_非压测任务_perf字段空_尾追零漂移() {
        long taskId = execTaskService.createExploreAct(projectId,
                "{\"kind\":\"explore-act\",\"action\":\"page-snapshot\","
                        + "\"url\":\"http://localhost:8899/login\"}");
        long executorId = executorService.register(new ExecutorRegisterInput(
                "ex-perf-null", null, null, "web", "chromium", 2, "v1", 0));

        ClaimTaskView view = execTaskService.claim(executorId);

        assertNotNull(view);
        assertEquals(taskId, view.taskId());
        assertNull(view.perf(), "非压测任务 perf=null（尾追兼容纪律，旧执行器零破坏）");
    }

    /**
     * Read line for the task badge: a perf task (task_type=5) surfaces both the
     * kind and the pinned runId through get/listByProject (the admin bridge and
     * the front-end badge deep link consume exactly these two fields); a non-perf
     * row keeps perfRunId=null (zero drift). Dirty envelope = null badge target,
     * the row still lists (fail-safe read, the list never breaks).
     */
    @Test
    void 读面贯通_压测行携taskType与perfRunId_非压测行恒空() {
        long runId = newScenarioRun();
        String envelope = "{\"kind\":\"perf-run\",\"runId\":" + runId
                + ",\"engine\":\"K6\",\"artifact\":\"/tmp/perf-" + runId + ".js\"}";
        long perfTaskId = execTaskService.createPerfRunTask(projectId, envelope);

        var perfInfo = execTaskService.get(perfTaskId);
        assertEquals(5, perfInfo.taskType(), "读面必须带出任务类型");
        assertEquals(runId, perfInfo.perfRunId(), "压测行必须带出钉版 runId（徽标深链目标）");

        var page = execTaskService.listByProject(projectId, 1, 50, null, null);
        var inList = page.stream().filter(t -> t.id() == perfTaskId).findFirst().orElseThrow();
        assertEquals(runId, inList.perfRunId(), "列表页同口径（admin 桥消费的就是这条线）");

        long otherTaskId = execTaskService.createExploreAct(projectId,
                "{\"kind\":\"explore-act\",\"action\":\"page-snapshot\","
                        + "\"url\":\"http://localhost:8899/login\"}");
        var otherInfo = execTaskService.get(otherTaskId);
        assertEquals(3, otherInfo.taskType());
        assertNull(otherInfo.perfRunId(), "非压测行 perfRunId=null（零漂移）");

        // Dirty perf row (envelope re-dirtied after the guarded insert): the badge
        // target reads null, the row itself still lists intact (fail-safe read).
        jdbcTemplate.update("UPDATE tp_exec_task SET action_json = '{\"kind\":\"perf-run\"}'"
                + " WHERE id = ?", perfTaskId);
        assertNull(execTaskService.get(perfTaskId).perfRunId(),
                "脏信封=徽标无目标诚实缺席，禁炸列表读路径");
    }

    private long lastInsertedId(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT max(id) FROM " + table, Long.class);
    }
}
