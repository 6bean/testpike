package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.component.PerfConfigReader;
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

/**
 * Launch-time duration ceiling for perf runs .
 * <p>Gap pinned by this class: before the fix, any scenario duration was accepted
 * at launch, so a soak declared for days could occupy an environment and the
 * dispatch budget indefinitely — the batching discipline (split long runs into
 * &lt;= ceiling batches) had no server-side precondition. The ceiling is the
 * hot-updatable K8 key perf.max-duration-hours (default 4h); a declared (or, for
 * RAMP, ramp+hold summed) duration above it is rejected inside the launch
 * transaction with a human reason naming the current value and the ceiling, and
 * the rejection leaves zero residue (no run row, no dispatch task).
 * <p>Fallback discipline: 0 / negative / non-numeric key values fall back to 4
 * (a dirty value can never switch the ceiling off); an unparseable or zero
 * duration declaration is not rejected by this gate (legacy shapes keep the
 * honest no-claim posture used by the A-14 budget and GA-2 occupant reads).
 * <p>Real chain, no mocks: dev DB fixtures + service calls (posture follows
 * PerfRunLaunchGateTest / PerfRunLaunchDispatchTest, PFGDUR prefix cascade cleanup).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfLaunchDurationGateTest {

    /** Ceiling key literal (same string PerfConfigReader exposes once the gate lands). */
    private static final String KEY_CEILING = "perf.max-duration-hours";

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
    void seedFixtures() {
        cleanupRows();
        long uniq = System.nanoTime() % 1_000_000_000L;
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "时长闸测试项目_" + uniq, "PFGDUR" + uniq);
        projectId = lastId("tp_project");
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '性能-时长闸', 1, 'http://perf-target.local/api')", projectId);
        envId = lastId("tp_project_env");
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, "
                + "allowed_modes) VALUES (?, ?, 1000, '[]'::jsonb)", projectId, envId);
        restoreSeed("4");
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
        restoreSeed("4");
    }

    private void cleanupRows() {
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFGDUR%')");
        jdbcTemplate.update("DELETE FROM tp_perf_sample WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFGDUR%')");
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFGDUR%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFGDUR%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFGDUR%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFGDUR%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFGDUR%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PFGDUR%'");
    }

    private long lastId(String table) {
        return jdbcTemplate.queryForObject("SELECT max(id) FROM " + table, Long.class);
    }

    /** Config write through ConfigService = cache synchronously invalidated (raw SQL update
     *  would leave a dirty entry poisoning later cases — known launch-gate lesson). */
    private void restoreSeed(String value) {
        Long id = jdbcTemplate.query("SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, KEY_CEILING);
        if (id != null) {
            configService.delete(null, id);
        }
        configService.create(null, new ConfigInput(KEY_CEILING,
                value, "系统", "int", 0,
                "perf duration ceiling managed by launch duration gate test"));
    }

    private long newScenario(String model, Map<String, Integer> config) {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                "时长闸场景_" + System.nanoTime(), envId, model, config, null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), null));
    }

    /** A CONSTANT_RPS run declared above the ceiling is rejected with a human reason
     *  naming the declared duration and the ceiling, and nothing is left behind. */
    @Test
    void 声明时长超上限_发起被拒且零残留() {
        long scenarioId = newScenario("CONSTANT_RPS",
                Map.of("targetRps", 20, "durationSeconds", 5 * 3600));

        ServiceException rejected = assertThrows(ServiceException.class,
                () -> perfRunService.launch(projectId, scenarioId, null));

        assertTrue(rejected.getMessage().contains("时长"),
                "rejection must name the duration ceiling: " + rejected.getMessage());
        assertTrue(rejected.getMessage().contains("5") && rejected.getMessage().contains("4"),
                "rejection must carry current value and ceiling: " + rejected.getMessage());
        assertEquals(0, count("tp_perf_run"));
        assertEquals(0, count("tp_exec_task"));
    }

    /** RAMP sums ramp+hold for the ceiling check (same duration source as A-14/GA-2). */
    @Test
    void 爬坡形态按时长加保持段合计判上限() {
        long scenarioId = newScenario("RAMP",
                Map.of("startRps", 10, "peakRps", 50,
                        "rampSeconds", 4 * 3600, "holdSeconds", 3600));

        ServiceException rejected = assertThrows(ServiceException.class,
                () -> perfRunService.launch(projectId, scenarioId, null));

        assertTrue(rejected.getMessage().contains("时长"),
                "RAMP must be judged by ramp+hold total: " + rejected.getMessage());
        assertEquals(0, count("tp_perf_run"));
    }

    /** Within-ceiling launches are untouched (zero-drift for the normal shape). */
    @Test
    void 上限内发起不受时长闸影响() {
        long scenarioId = newScenario("CONSTANT_RPS",
                Map.of("targetRps", 20, "durationSeconds", 3600));

        long runId = perfRunService.launch(projectId, scenarioId, null);

        assertTrue(runId > 0);
        assertEquals(1, count("tp_perf_run"));
    }

    /** The ceiling is hot-updatable: key=1h rejects a 2h declaration. */
    @Test
    void 上限键热更_一小时闸拒两小时声明() {
        restoreSeed("1");
        long scenarioId = newScenario("CONSTANT_RPS",
                Map.of("targetRps", 20, "durationSeconds", 2 * 3600));

        assertThrows(ServiceException.class,
                () -> perfRunService.launch(projectId, scenarioId, null));
        assertEquals(0, count("tp_perf_run"));
    }

    /** Dirty ceiling values (0 / negative / non-numeric) fall back to 4h — the gate
     *  can never be switched off by a mistyped config row. */
    @Test
    void 非法上限值回退四小时不关闸() {
        for (String dirty : List.of("0", "-1", "abc")) {
            restoreSeed(dirty);
            long scenarioId = newScenario("CONSTANT_RPS",
                    Map.of("targetRps", 20, "durationSeconds", 5 * 3600));
            assertThrows(ServiceException.class,
                    () -> perfRunService.launch(projectId, scenarioId, null),
                    "dirty value '" + dirty + "' must not disable the ceiling");
        }
        assertEquals(0, count("tp_perf_run"));
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table
                        + " WHERE project_id = " + projectId, Integer.class);
    }
}
