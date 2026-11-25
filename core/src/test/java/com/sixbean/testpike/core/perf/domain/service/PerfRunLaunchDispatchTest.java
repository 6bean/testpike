package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.component.PerfConfigReader;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfRunLaunchDispatchTest {    /** Suite-exclusive executor seat prefix (fresh-install determinism). */
    private static final String EXEC_PREFIX = "tpf-perf-ldisp-";
    private com.sixbean.testpike.core.support.FleetSeatFixture fleetSeat;


    @Autowired
    private ExecTaskService execTaskService;

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private com.sixbean.testpike.core.perf.domain.component.PerfRunDispatchSupport
            perfRunDispatchSupport;

    @Autowired
    private com.sixbean.testpike.core.executor.domain.component.ArtifactStorage artifactStorage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;

    @BeforeEach
    void seedLaunchFixture() {
        // Fresh-install determinism: this suite routes on the shared fleet
        // registry, so it brings its own engine-capable seat and quiesces the
        // rest of the online view for the window (single-source fixture, R40).
        // The suite pins JMETER in one envelope case, so the seat must
        // advertise every engine value the routing gate can match on
        // (PerfRunDispatchSupport.ENGINE_VALUES = K6/GATLING/JMETER).
        fleetSeat = com.sixbean.testpike.core.support.FleetSeatFixture.open(jdbcTemplate, EXEC_PREFIX, "GATLING", "K6", "JMETER");

        cleanupLaunchRows();
        long[] ids = SharedDbFixtureSweep.seedPerfFixtureProject(jdbcTemplate,
                "派发链测试项目_", "PERFLAUNCH",
                "性能-派发链", "http://perf-target.local/api");
        projectId = ids[0];
        envId = ids[1];
    }

    @AfterEach
    void cleanupLaunchFixture() {
        cleanupLaunchRows();
    
        if (fleetSeat != null) {
            fleetSeat.close();
            fleetSeat = null;
        }
}

    private void cleanupLaunchRows() {
        Long engineCfgId = jdbcTemplate.query("SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, PerfConfigReader.KEY_DEFAULT_ENGINE);
        if (engineCfgId != null) {
            configService.delete(null, engineCfgId);
        }
        configService.create(null, new ConfigInput(PerfConfigReader.KEY_DEFAULT_ENGINE,
                "GATLING", "系统", "string", 0,
                "压测默认引擎（D1 裁决 Gatling 默认/k6 备选；值域 K6/GATLING，非法值回退默认）"));
        // Fixture-scoped teardown :
        // the PERFLAUNCH project family with its FK tree (reaches tp_exec_task and the
        // tp_perf_* tables). The old blanket TRUNCATE of tp_exec_task/tp_executor wiped
        // rows owned by concurrent suites and the persistent runner's registration.
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, jdbcTemplate.queryForList(
                "SELECT id FROM tp_project WHERE code LIKE 'PERFLAUNCH%'", Long.class));
    }

    private long newScenario() {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                "派发链场景_" + System.nanoTime(), envId, "CONSTANT_RPS",
                Map.of("targetRps", 40, "durationSeconds", 60), null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), null));
    }

    
    private void setDefaultEngine(String engine) {
        Long existing = jdbcTemplate.query("SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, PerfConfigReader.KEY_DEFAULT_ENGINE);
        if (existing != null) {
            configService.delete(null, existing);
        }
        configService.create(null, new ConfigInput(PerfConfigReader.KEY_DEFAULT_ENGINE,
                engine, "系统", "string", 0, "压测默认引擎（K8 口径可配热更；D1 裁决默认 GATLING）"));
    }

    private Map<String, Object> taskForRun(long runId) {
        return jdbcTemplate.queryForMap("SELECT task_type, status, schema_version, action_json "
                + "FROM tp_exec_task WHERE project_id = ? AND schema_version = 'perf-v1'", projectId);
    }

    
    @Test
    void launch_默认引擎k6_自动建派发任务_信封与产物落盘齐() {
        setDefaultEngine("K6");
        long scenarioId = newScenario();

        long runId = perfRunService.launch(projectId, scenarioId, null);

        Map<String, Object> task = taskForRun(runId);
        assertEquals(5, ((Number) task.get("task_type")).intValue(), "压测派发任务必须 task_type=5");
        assertEquals("perf-v1", task.get("schema_version"));
        String envelope = (String) task.get("action_json");
        assertTrue(envelope.contains("\"kind\":\"perf-run\""), "信封 kind 同源: " + envelope);
        assertTrue(envelope.contains("\"runId\":" + runId), "信封 runId 指向本次运行: " + envelope);
        assertTrue(envelope.contains("\"engine\":\"K6\""), "信封引擎=解析值: " + envelope);

        String artifact = envelope.replaceAll(".*\"artifact\":\"([^\"]+)\".*", "$1");
        // (A-10) correction: the handle is a project-routed object
        // key (zip bundle), not a shared-temp path — readability rides the SAME
        // write route (read/write single source) and the envelope carries the key
        // so any executor node can materialize it.
        assertTrue(artifactStorage.exists(artifact),
                "产物句柄必须是写路由可读的对象键: " + artifact);
        assertTrue(artifact.endsWith(".zip"), "k6 产物=zip 包对象键: " + artifact);

        assertEquals(1, ((Number) perfRunService.view(projectId, runId).get("status")).intValue(),
                "运行行保持运行中（派发成功不反写状态）");
    }

    
    @Test
    void launch_键缺位_默认引擎gatling_D1裁决字面() {
        long scenarioId = newScenario();

        long runId = perfRunService.launch(projectId, scenarioId, null);

        String envelope = (String) taskForRun(runId).get("action_json");
        assertTrue(envelope.contains("\"engine\":\"GATLING\""),
                "D1 裁决 Gatling 默认位，键缺位不得静默切 k6: " + envelope);
        String artifact = envelope.replaceAll(".*\"artifact\":\"([^\"]+)\".*", "$1");
        // (A-10) correction: the handle is an object key of the run
        // directory zip; the on-disk tree is only javac scratch.
        assertTrue(artifactStorage.exists(artifact),
                "Gatling 仿真产物必须入库可读: " + artifact);
    }

    
    @Test
    void launch_默认引擎键非法值_回退GATLING() {
        setDefaultEngine("BOOM");
        long scenarioId = newScenario();

        long runId = perfRunService.launch(projectId, scenarioId, null);

        assertTrue(((String) taskForRun(runId).get("action_json")).contains("\"engine\":\"GATLING\""),
                "非法引擎值回退默认（禁把脏值透传信封）");
    }

    
    @Test
    void launch_编译失败_运行行零残留_人话点名() {
        setDefaultEngine("K6");
        long scenarioId = newScenario();
        jdbcTemplate.update("UPDATE tp_perf_scenario SET steps_json = "
                + "'[{\"action\":\"page.click\",\"selector\":\"#x\"}]'::jsonb WHERE id = ?", scenarioId);

        ServiceException e = assertThrows(ServiceException.class,
                () -> perfRunService.launch(projectId, scenarioId, null));
        assertTrue(e.getMessage().contains("压测") && e.getMessage().contains("启动失败"),
                "人话点名含启动失败语义: " + e.getMessage());

        Integer runs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_perf_run WHERE project_id = ?", Integer.class, projectId);
        assertEquals(0, runs, "编译失败不得留 status=1 孤儿运行行");
        Integer tasks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE project_id = ?", Integer.class, projectId);
        assertEquals(0, tasks, "失败事务不得留派发任务行");
    }

    /**
 * GA-2 environment lock (PerfEnvExclusionGuard) fixes one in-flight load per
 * environment, so run A is moved to a terminal state before the second launch
 * — the per-launch "exactly one dispatch task" intent of this test is kept. */
    @Test
    void launch_两次发起_各自任务行信封指向各自运行() {
        setDefaultEngine("K6");
        long scenarioId = newScenario();

        long runA = perfRunService.launch(projectId, scenarioId, null);
        // Finish run A first: GA-2 serialises environments by design (one in-flight
        // load per env), so the second launch becomes legal only after A lands in a
        // terminal state — status=4 mirrors the finalizer's single-source write.
        jdbcTemplate.update("UPDATE tp_perf_run SET status = 4 WHERE id = ?", runA);
        long runB = perfRunService.launch(projectId, scenarioId, null);

        List<String> envelopes = jdbcTemplate.queryForList(
                "SELECT action_json FROM tp_exec_task WHERE project_id = ? ORDER BY id",
                String.class, projectId);
        assertEquals(2, envelopes.size(), "每次发起恰好一条派发任务");
        assertTrue(envelopes.get(0).contains("\"runId\":" + runA), "首条指向 runA");
        assertTrue(envelopes.get(1).contains("\"runId\":" + runB), "次条指向 runB");
    }

    
    @Test
    void launch_派发信封可被claim装配_同源键闭环() {
        setDefaultEngine("K6");
        long scenarioId = newScenario();
        long runId = perfRunService.launch(projectId, scenarioId, null);

        String envelope = (String) taskForRun(runId).get("action_json");
        Map<String, Object> perf = perfRunDispatchSupport.assembleForClaim(projectId, envelope);
        assertTrue(perf != null, "launch 产物信封必须可被 claim 装配（脏单=链断）");
        assertEquals(runId, ((Number) perf.get("runId")).longValue());
        assertEquals("K6", perf.get("engine"));
        assertTrue(String.valueOf(perf.get("pressureSnapshot")).contains("targetRps"),
                "claim 下发启动钉版快照（T280 同纪律）");
        // : the single-batch gate value rides the claim so the executor
        // chunks to the gate's own number (single source = PerfMetricServiceImpl,
        // never a duplicated literal on the executor side).
        assertEquals(com.sixbean.testpike.core.perf.domain.service.impl
                        .PerfMetricServiceImpl.MAX_BATCH_FRAMES,
                ((Number) perf.get("maxShipFrames")).intValue(),
                "dispatch context projects the ingestion volume gate = single numeric source for executor chunking");
    }

    
    @Test
    void launch_场景显式绑定引擎_信封钉场景值优先于默认引擎() {
        setDefaultEngine("GATLING");
        long scenarioId = perfScenarioService.create(projectId, new PerfScenarioInput(
                "钉版JMETER派发场景_" + System.nanoTime(), envId, "CONSTANT_RPS",
                Map.of("targetRps", 40, "durationSeconds", 60), null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), null,
                null, null, null, null, null, null,
                "DSL", null, "JMETER"));

        long runId = perfRunService.launch(projectId, scenarioId, null);

        String envelope = (String) taskForRun(runId).get("action_json");
        assertTrue(envelope.contains("\"engine\":\"JMETER\""),
                "信封引擎=场景钉版值而非默认引擎: " + envelope);
    }

    
    @Test
    void launch_场景留空引擎_派发沿默认引擎零漂移() {
        setDefaultEngine("K6");
        long scenarioId = newScenario();
        long runId = perfRunService.launch(projectId, scenarioId, null);
        String envelope = (String) taskForRun(runId).get("action_json");
        assertTrue(envelope.contains("\"engine\":\"K6\""), "留空=默认路由: " + envelope);
    }
}
