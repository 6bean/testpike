package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Residual-cleanup guide assembly tests (GB-4, ): the report's residualGuide
 * key must ride the launch-pinned snapshot only — declared modes render their
 * guide, NS_ACCOUNT draws the recycle list from the pinned dataset's ledger row
 * (a ledger row deleted after launch degrades the wording honestly), and an
 * undeclared run gets null (never a guide invented after the fact). Real dev DB
 * via SpringBootTest (no mocks), fixture posture mirrors PerfReportServiceTest.
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfReportResidualGuideTest {

    @Autowired
    private PerfReportService perfReportService;

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private PerfDatasetService perfDatasetService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;

    @BeforeEach
    void seedFixtures() {
        cleanupRows();
        long uniq = System.nanoTime() % 1_000_000_000L;
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "残留巡检测试项目_" + uniq, "PERFREPG" + uniq);
        projectId = lastId("tp_project");
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                + "VALUES (?, '性能-残留测', 1, 'http://perf-target.local/api')", projectId);
        envId = lastId("tp_project_env");
        // The launch gate's whitelist door checks declared isolation modes against
        // allowed_modes, so the fixture registers the full roster (declare-and-admit).
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, "
                + "allowed_modes) VALUES (?, ?, 1000, "
                + "'[\"SHADOW_TABLE\",\"NS_ACCOUNT\",\"HEADER_MARK\"]'::jsonb)", projectId, envId);
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
    }

    private void cleanupRows() {
        for (String sql : List.of(
                "DELETE FROM tp_perf_sample WHERE project_id IN (SELECT id FROM tp_project WHERE code LIKE 'PERFREPG%')",
                "DELETE FROM tp_perf_metric WHERE project_id IN (SELECT id FROM tp_project WHERE code LIKE 'PERFREPG%')",
                "DELETE FROM tp_perf_run WHERE project_id IN (SELECT id FROM tp_project WHERE code LIKE 'PERFREPG%')",
                "DELETE FROM tp_perf_scenario WHERE project_id IN (SELECT id FROM tp_project WHERE code LIKE 'PERFREPG%')",
                "DELETE FROM tp_perf_dataset WHERE project_id IN (SELECT id FROM tp_project WHERE code LIKE 'PERFREPG%')",
                "DELETE FROM tp_perf_whitelist WHERE project_id IN (SELECT id FROM tp_project WHERE code LIKE 'PERFREPG%')",
                "DELETE FROM tp_project_env WHERE project_id IN (SELECT id FROM tp_project WHERE code LIKE 'PERFREPG%')",
                "DELETE FROM tp_project WHERE code LIKE 'PERFREPG%'")) {
            jdbcTemplate.update(sql);
        }
    }

    private long lastId(String table) {
        return jdbcTemplate.queryForObject("SELECT id FROM " + table + " ORDER BY id DESC LIMIT 1",
                Long.class);
    }

    private long newScenario(String isolationMode, Long datasetId) {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                "残留巡检场景_" + System.nanoTime(), envId, "CONSTANT_RPS",
                Map.of("targetRps", 50, "durationSeconds", 600), null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), null,
                isolationMode, datasetId, null, null, null, null, null, null, null));
    }

    /** Pool uploaded through the real dataset service — the launch dispatcher
     *  fail-closes when the ledger row has no object behind it, so the fixture
     *  must create a genuinely stored pool (live-fixture discipline). */
    private long newAccountPoolDataset() {
        StringBuilder csv = new StringBuilder("username,password,userId\n");
        for (int i = 0; i < 300; i++) {
            csv.append("acct").append(i).append(",pwd").append(i).append(",").append(i).append("\n");
        }
        return perfDatasetService.upload(projectId, "账号池A", "acct.csv",
                csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Undeclared isolation = the honest absence on the report face. */
    @Test
    void undeclaredRunReportsNoResidualGuide() {
        long runId = perfRunService.launch(projectId, newScenario(null, null), null);

        Map<String, Object> report = perfReportService.build(projectId, runId);

        assertThat(report).containsKey("residualGuide");
        assertThat(report.get("residualGuide")).isNull();
    }

    /** HEADER_MARK pinned at launch = sampling-verification sheet naming the
     *  station-wide mark header. */
    @Test
    void headerMarkRunCarriesSamplingVerificationGuide() {
        long runId = perfRunService.launch(projectId, newScenario("HEADER_MARK", null), null);

        Map<String, Object> guide = asMap(perfReportService.build(projectId, runId)
                .get("residualGuide"));

        assertThat(guide).isNotNull();
        assertThat(guide.get("mode")).isEqualTo("HEADER_MARK");
        assertThat(String.valueOf(guide.get("sections"))).contains("X-Load-Test");
    }

    /** NS_ACCOUNT with a bound pool: the recycle list names the pinned dataset,
     *  its account-shaped columns and the row count. */
    @Test
    void nsAccountRunDrawsRecycleListFromPinnedDataset() {
        long datasetId = newAccountPoolDataset();
        long runId = perfRunService.launch(projectId, newScenario("NS_ACCOUNT", datasetId), null);

        Map<String, Object> guide = asMap(perfReportService.build(projectId, runId)
                .get("residualGuide"));

        String text = String.valueOf(guide);
        assertThat(guide.get("mode")).isEqualTo("NS_ACCOUNT");
        assertThat(text).contains("账号池A").contains("username").contains("300");
    }

    /** The guide follows the pinned dataset id, not the live scenario binding:
     *  removing the ledger row after launch degrades the wording honestly
     *  (recycle pass still due, no fabricated list). The ledger row is removed
     *  along the real operational chain (archive the scenario first — runs keep
     *  their pinned snapshot through it). */
    @Test
    void nsAccountRunAfterDatasetDeletionDegradesHonestly() {
        long datasetId = newAccountPoolDataset();
        long scenarioId = newScenario("NS_ACCOUNT", datasetId);
        long runId = perfRunService.launch(projectId, scenarioId, null);
        // Unbind then remove the ledger row (FK order) — the run's pinned snapshot
        // still names the dataset id, which is exactly what the guide must face.
        jdbcTemplate.update("UPDATE tp_perf_scenario SET dataset_id = NULL WHERE id = ?", scenarioId);
        jdbcTemplate.update("DELETE FROM tp_perf_dataset WHERE id = ?", datasetId);

        Map<String, Object> guide = asMap(perfReportService.build(projectId, runId)
                .get("residualGuide"));

        assertThat(String.valueOf(guide)).contains("未绑定");
    }

    /** SHADOW_TABLE = counting sheet with the SUT-side boundary note (the
     *  platform never claims it scanned the shadow tables itself). */
    @Test
    void shadowTableRunCarriesCountingGuideWithBoundaryNote() {
        long runId = perfRunService.launch(projectId, newScenario("SHADOW_TABLE", null), null);

        Map<String, Object> guide = asMap(perfReportService.build(projectId, runId)
                .get("residualGuide"));

        assertThat(String.valueOf(guide.get("sections"))).contains("影子表");
        assertThat(String.valueOf(guide.get("boundaryNote"))).contains("被测侧");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map ? (Map<String, Object>) raw : null;
    }
}
