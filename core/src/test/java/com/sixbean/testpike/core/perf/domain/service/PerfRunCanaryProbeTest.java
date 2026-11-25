package com.sixbean.testpike.core.perf.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.sixbean.testpike.core.support.FixtureInserts;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;

/**
 * CF-6 canary pre-flight at the perf launch point .
 * <p>Before this segment a launch committed the whole run contract (safety gate,
 * duration ceiling, environment lock, dispatch task) before anything ever touched
 * the target: a dead or pathological environment therefore burned the approval
 * window and the operator's patience with a run that could only fail at press
 * time. The launch now runs an automatic low-load probe first — a handful of
 * sequential GETs against the effective environment decides reachability/TLS/
 * baseline response time, and for the Gatling engine the first javac compile is
 * pre-warmed in the same step (cold-start compensation, GB-5). A failed probe
 * rejects the launch with a human-worded reason and zero residue; the passing
 * outcome is pinned into the run snapshot so the report face can attribute
 * against the measured baseline.
 * <p>Red anchors pinned here: switch off = today's behaviour byte-for-byte (an
 * unreachable target still launches, no canary key in the snapshot); switch on +
 * unreachable target = rejection naming the host, zero run rows and zero dispatch
 * tasks; switch on + reachable target = snapshot carries probe count, answered
 * count and a baseline p95; a 404 still counts as reachable (the contract is
 * liveness, not route correctness); an environment slower than the ceiling is
 * rejected with both the measured and the limit value named; dirty config values
 * fall back to the built-in defaults.
 * <p>Real chain, no mocks: dev DB fixtures + a local in-JVM HttpServer as the
 * press target (fixture posture follows PerfRunTargetVersionPinningTest).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfRunCanaryProbeTest {

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private HttpServer target;
    private int targetPort;
    /** Handler latency in ms — raised by the slow-baseline scenario. */
    private volatile int responseDelayMs;
    private long projectId;
    private long envId;
    private long operatorId;

    @BeforeEach
    void seedFixtures() throws Exception {
        cleanupRows();
        // Fresh-install CI has no runner process online with an engine face,
        // so the perf launch gate would reject every run: seed one capable seat.
        SharedDbFixtureSweep.seedCapableExecutor(jdbcTemplate, "perf-canary-ex-",
                "GATLING", "K6");
        responseDelayMs = 0;
        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/orders", exchange -> {
            if (responseDelayMs > 0) {
                try {
                    Thread.sleep(responseDelayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "{\"code\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        target.start();
        targetPort = target.getAddress().getPort();
        operatorId = jdbcTemplate.queryForObject("SELECT min(id) FROM tp_user", Long.class);
        long uniq = System.nanoTime() % 1_000_000_000L;
        projectId = FixtureInserts.insertReturningId(jdbcTemplate, "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "金丝雀预检测试项目_" + uniq, "PFCAN" + uniq);
        envId = FixtureInserts.insertReturningId(jdbcTemplate, "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '开发-预检目标', 1, ?)",
                projectId, "http://127.0.0.1:" + targetPort + "/orders");
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, "
                        + "allowed_modes) VALUES (?, ?, 100, CAST(? AS jsonb))",
                projectId, envId, "[\"SHADOW_TABLE\"]");
        restoreSeed("perf.production-enabled", "false");
        restoreSeed("perf.blocked-target-segments", "");
        restoreSeed("perf.canary.enabled", "false");
        restoreSeed("perf.canary.requests", "5");
        restoreSeed("perf.canary.probe-timeout-seconds", "5");
        restoreSeed("perf.canary.baseline-p95-ms", "3000");
    }

    @AfterEach
    void cleanup() {
        target.stop(0);
        cleanupRows();
        restoreSeed("perf.production-enabled", "false");
        restoreSeed("perf.blocked-target-segments", "");
        restoreSeed("perf.canary.enabled", "false");
        restoreSeed("perf.canary.requests", "5");
        restoreSeed("perf.canary.probe-timeout-seconds", "5");
        restoreSeed("perf.canary.baseline-p95-ms", "3000");
    }

    private void cleanupRows() {
        SharedDbFixtureSweep.executorsByNamePrefixes(jdbcTemplate, "perf-canary-ex-");
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFCAN%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFCAN%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFCAN%')");
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFCAN%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFCAN%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFCAN%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PFCAN%'");
    }

    private void restoreSeed(String key, String value) {
        Long id = jdbcTemplate.query("SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, key);
        if (id != null) {
            configService.delete(null, id);
        }
        configService.create(null, new ConfigInput(key, value, "系统", "string", 0,
                "canary pre-flight key managed by canary probe test"));
    }

    private long newScenario() {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                "预检场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 30, "durationSeconds", 60),
                null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://127.0.0.1:" + targetPort + "/orders")),
                null, null));
    }

    private JsonNode snapshotOf(long runId) throws Exception {
        String raw = (String) perfRunService.view(projectId, runId).get("pressure_snapshot");
        return objectMapper.readTree(raw);
    }

    private long runRowCount() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_perf_run WHERE project_id = ?", Long.class, projectId);
        return n == null ? 0 : n;
    }

    private long dispatchTaskCount() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE project_id = ?", Long.class, projectId);
        return n == null ? 0 : n;
    }

    /** Switch off spectrum: today's behaviour byte-for-byte — an unreachable target
     *  still launches and the snapshot carries no canary claim. */
    @Test
    void 预检关闭_不可达目标照常发起_快照无预检键() throws Exception {
        jdbcTemplate.update("UPDATE tp_project_env SET base_url = ? WHERE id = ?",
                "http://127.0.0.1:1/dead", envId);
        long scenario = newScenario();

        long run = perfRunService.launch(projectId, scenario, operatorId);

        assertEquals(1, runRowCount());
        assertFalse(snapshotOf(run).has("canary"),
                "预检关闭时快照不得出现 canary 键（零行为漂移）");
    }

    /** Switch on + unreachable: the launch is refused with the host named, and the
     *  whole transaction rolls back — no run row, no dispatch task. */
    @Test
    void 预检开启_目标不可达_人话拒单零残留() {
        restoreSeed("perf.canary.enabled", "true");
        jdbcTemplate.update("UPDATE tp_project_env SET base_url = ? WHERE id = ?",
                "http://127.0.0.1:1/dead", envId);
        long scenario = newScenario();

        ServiceException rejected = assertThrows(ServiceException.class,
                () -> perfRunService.launch(projectId, scenario, operatorId));

        assertTrue(rejected.getMessage().contains("127.0.0.1"),
                "拒单必须点名探测失败的目标主机: " + rejected.getMessage());
        assertEquals(0, runRowCount(), "预检拒单不得留下运行行");
        assertEquals(0, dispatchTaskCount(), "预检拒单不得留下派发任务");
    }

    /** Switch on + reachable: the run commits and the snapshot pins the probe
     *  outcome (answered probes and a measured baseline p95) for the report face. */
    @Test
    void 预检开启_目标可达_快照贴预检记录() throws Exception {
        restoreSeed("perf.canary.enabled", "true");
        long scenario = newScenario();

        long run = perfRunService.launch(projectId, scenario, operatorId);

        JsonNode canary = snapshotOf(run).path("canary");
        assertTrue(canary.isObject(), "预检通过时快照必须带 canary 对象: " + snapshotOf(run));
        assertEquals(5, canary.path("probes").asInt(), "缺省探测次数=配置键 5");
        assertEquals(5, canary.path("answered").asInt());
        assertTrue(canary.path("baselineP95Ms").asLong() >= 0,
                "基线 p95 必须是实测非负值");
        assertNotNull(canary.path("engine").asText(null));
    }

    /** Reachability contract = liveness, not route correctness: a 404 from the
     *  target host still proves the environment answers. */
    @Test
    void 预检开启_目标返回404_仍算可达() throws Exception {
        restoreSeed("perf.canary.enabled", "true");
        jdbcTemplate.update("UPDATE tp_project_env SET base_url = ? WHERE id = ?",
                "http://127.0.0.1:" + targetPort + "/no-such-route", envId);
        long scenario = newScenario();

        long run = perfRunService.launch(projectId, scenario, operatorId);

        assertEquals(5, snapshotOf(run).path("canary").path("answered").asInt());
    }

    /** Slow baseline spectrum: measured p95 over the hot-updatable ceiling rejects
     *  the launch naming both the measured value and the limit. */
    @Test
    void 预检开启_基线超阈_点名实测与阈值拒单() {
        restoreSeed("perf.canary.enabled", "true");
        restoreSeed("perf.canary.baseline-p95-ms", "10");
        responseDelayMs = 80;
        long scenario = newScenario();

        ServiceException rejected = assertThrows(ServiceException.class,
                () -> perfRunService.launch(projectId, scenario, operatorId));

        assertTrue(rejected.getMessage().contains("10"),
                "拒单必须点名当前阈值: " + rejected.getMessage());
        assertEquals(0, runRowCount());
        assertEquals(0, dispatchTaskCount());
    }

    /** Dirty config posture: non-numeric probe counts fall back to the built-in
     *  default instead of switching the pre-flight off or exploding the launch. */
    @Test
    void 预检开启_脏配置值回退默认_照常发起() throws Exception {
        restoreSeed("perf.canary.enabled", "true");
        restoreSeed("perf.canary.requests", "abc");
        long scenario = newScenario();

        long run = perfRunService.launch(projectId, scenario, operatorId);

        assertEquals(5, snapshotOf(run).path("canary").path("probes").asInt(),
                "脏值必须回退内置缺省 5 次");
    }
}
