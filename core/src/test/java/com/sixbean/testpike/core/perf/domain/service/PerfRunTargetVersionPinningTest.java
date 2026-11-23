package com.sixbean.testpike.core.perf.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * GA-5 target-version pinning at the perf launch point .
 * <p>Before this segment a run snapshot recorded which environment was pressed but
 * never which deployed build that environment carried: an upgrade-vs-baseline perf
 * comparison could not self-prove "both runs pressed version X" — the exact question
 * a capacity regression after a release deployment needs answered. The launch now
 * freezes the effective environment's recorded deployment version
 * (tp_project_env.version_snapshot, the same field the deploy-status face maintains)
 * into the pinned snapshot as {@code targetVersion}.
 * <p>Red anchors pinned here: a default-environment launch pins that environment's
 * version; a GB-1 override launch pins the overridden environment's version (never
 * the scenario default's — the pin must describe what was actually pressed); an
 * environment with no recorded version pins an honest null (no fabricated label);
 * a rejection at the gate still leaves zero run rows with the new key in play.
 * <p>Real chain, no mocks: dev DB fixtures + service calls (fixture posture follows
 * PerfLaunchEnvOverrideTest).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfRunTargetVersionPinningTest {

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

    private long projectId;
    private long envAId;
    private long envBId;
    private long operatorId;

    @BeforeEach
    void seedFixtures() {
        cleanupRows();
        operatorId = jdbcTemplate.queryForObject("SELECT min(id) FROM tp_user", Long.class);
        long uniq = System.nanoTime() % 1_000_000_000L;
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "版本钉注测试项目_" + uniq, "PFVERO" + uniq);
        projectId = lastId("tp_project");
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '开发-版本A', 1, 'http://perf-a.local/api')", projectId);
        envAId = lastId("tp_project_env");
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '测试-版本B', 2, 'http://perf-b.local/api')", projectId);
        envBId = lastId("tp_project_env");
        restoreSeed("perf.production-enabled", "false");
        restoreSeed("perf.blocked-target-segments", "");
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
        restoreSeed("perf.production-enabled", "false");
        restoreSeed("perf.blocked-target-segments", "");
    }

    private void cleanupRows() {
        for (String prefix : List.of("PFVERO")) {
            jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                    + "(SELECT id FROM tp_project WHERE code LIKE '" + prefix + "%')");
            jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                    + "(SELECT id FROM tp_project WHERE code LIKE '" + prefix + "%')");
            jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                    + "(SELECT id FROM tp_project WHERE code LIKE '" + prefix + "%')");
            jdbcTemplate.update("DELETE FROM tp_perf_approval WHERE project_id IN "
                    + "(SELECT id FROM tp_project WHERE code LIKE '" + prefix + "%')");
            jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id IN "
                    + "(SELECT id FROM tp_project WHERE code LIKE '" + prefix + "%')");
            jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE project_id IN "
                    + "(SELECT id FROM tp_project WHERE code LIKE '" + prefix + "%')");
            jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                    + "(SELECT id FROM tp_project WHERE code LIKE '" + prefix + "%')");
            jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                    + "(SELECT id FROM tp_project WHERE code LIKE '" + prefix + "%')");
            jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE '" + prefix + "%'");
        }
    }

    private long lastId(String table) {
        return jdbcTemplate.queryForObject("SELECT max(id) FROM " + table, Long.class);
    }

    private void restoreSeed(String key, String value) {
        Long id = jdbcTemplate.query("SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, key);
        if (id != null) {
            configService.delete(null, id);
        }
        configService.create(null, new ConfigInput(key, value, "系统", "string", 0,
                "target-version pin seed managed by version-pinning test"));
    }

    private void setEnvVersion(long envId, String version) {
        jdbcTemplate.update("UPDATE tp_project_env SET version_snapshot = ? WHERE id = ?",
                version, envId);
    }

    private void addWhitelist(long envId, int maxConcurrency) {
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, "
                        + "allowed_modes) VALUES (?, ?, ?, CAST(? AS jsonb))",
                projectId, envId, maxConcurrency, "[\"SHADOW_TABLE\"]");
    }

    private long newScenario(long envId, String name) {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                name, envId, "CONSTANT_RPS",
                Map.of("targetRps", 30, "durationSeconds", 60),
                null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-a.local/api/orders")),
                null, null));
    }

    private JsonNode snapshotOf(long runId) throws Exception {
        String raw = (String) perfRunService.view(projectId, runId).get("pressure_snapshot");
        return objectMapper.readTree(raw);
    }

    /** Default spectrum: the scenario-bound environment's recorded deployment version
     *  is frozen into the pinned snapshot at launch. */
    @Test
    void launch_默认环境_快照钉住该环境部署版本() throws Exception {
        addWhitelist(envAId, 100);
        setEnvVersion(envAId, "release-2026.10.24");
        long scenario = newScenario(envAId, "默认版本场景");

        long run = perfRunService.launch(projectId, scenario, operatorId);

        assertEquals("release-2026.10.24", snapshotOf(run).path("targetVersion").asText(),
                "快照必须钉住所选环境的部署版本: " + snapshotOf(run).toString());
    }

    /** GB-1 override spectrum: an explicit launch-time environment override must pin
     *  the override environment's version, never the scenario default's — the pin
     *  describes what was actually pressed. */
    @Test
    void launch_覆盖选环境B_钉住B的版本非默认A() throws Exception {
        addWhitelist(envAId, 100);
        addWhitelist(envBId, 100);
        setEnvVersion(envAId, "release-A");
        setEnvVersion(envBId, "release-B");
        long scenario = newScenario(envAId, "覆盖版本场景");

        long run = perfRunService.launch(projectId, scenario, envBId, operatorId);

        assertEquals(envBId, snapshotOf(run).path("envId").asLong());
        assertEquals("release-B", snapshotOf(run).path("targetVersion").asText(),
                "覆盖发起必须钉住实际被压环境 B 的版本: " + snapshotOf(run).toString());
    }

    /** Honesty spectrum: an environment with no recorded deployment version pins an
     *  explicit null — never a fabricated or stale label. */
    @Test
    void launch_环境无版本记录_快照诚实空() throws Exception {
        addWhitelist(envAId, 100);
        long scenario = newScenario(envAId, "无版本场景");

        long run = perfRunService.launch(projectId, scenario, operatorId);

        assertTrue(snapshotOf(run).has("targetVersion"),
                "键必须在位（钉版契约含该字段），值诚实为 null: " + snapshotOf(run).toString());
        assertTrue(snapshotOf(run).get("targetVersion").isNull(),
                "无部署版本记录的环境必须钉 null，禁伪造标注");
    }

    /** The new snapshot key must not disturb the DSL fingerprint: the fingerprint only
     *  covers DSL-bearing keys, so pinning a version label (identity data, like envId)
     *  never changes comparability. Also keeps the fail-closed gate posture intact: a
     *  rejected launch leaves zero rows. */
    @Test
    void launch_版本标注不进DSL指纹_且拒单零残留() throws Exception {
        setEnvVersion(envAId, "release-x");
        long scenario = newScenario(envAId, "指纹守恒场景");

        // envA unwhitelisted here: gate rejects, zero residue (same posture as siblings).
        ServiceException e = assertThrows(ServiceException.class,
                () -> perfRunService.launch(projectId, scenario, operatorId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_perf_run WHERE project_id = ?", Integer.class, projectId),
                "被拒发起零运行行: " + e.getMessage());
    }

    private static void assertTrue(boolean value, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(value, message);
    }
}
