package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.component.JmxTemplate;
import com.sixbean.testpike.core.perf.domain.component.PerfMetricFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3 report-face approximation disclosure (W-d disclosure face, core half).
 * <p>Gap this test anchors: the calibre footnote assembled by the report is
 * engine-blind, yet a JMeter run presses its declared RPS through per-second
 * timer smoothing (VU driver approximation) — the reader of the numbers must
 * be told the caliber programmatically, reusing the JMX-embedded single-source
 * wording rather than a second paraphrase.
 * <p>Red anchors: a run whose scenario engine is JMETER ships a footnote
 * embedding {@link JmxTemplate#APPROXIMATION_NOTE} verbatim; the default-engine
 * (GATLING) control run ships a footnote without the approximation wording
 * (no disclosure noise on exact-schedule engines).
 * <p>Real chain, no mocks: dev DB fixtures + service calls. The run row is
 * pinned directly instead of going through launch: this face proves how the
 * report reads the pinned snapshot plus the scenario engine, while launch
 * additionally rides the executor-dispatch capability gate — the dev window
 * currently has zero online executors advertising GATLING/JMETER, so a
 * dispatch-bound fixture would prove executor presence, not report calibre.
 * The pinned row keeps launch's intake shape (status running — the metric
 * gate refuses frames for a finished run — plus JSONB pressure_snapshot) and
 * the frames flow through the real recordFrames → report chain.
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfReportJmeterDisclosureNoteTest {

    @Autowired
    private PerfReportService perfReportService;

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfMetricService perfMetricService;

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
                "JMeter口径披露测试项目_" + uniq, "PFJMTR" + uniq);
        projectId = lastId("tp_project");
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '测试-口径披露', 2, 'https://perf-jmeter-disclosure.local/api')",
                projectId);
        envId = lastId("tp_project_env");
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, "
                        + "allowed_modes) VALUES (?, ?, 100, CAST(? AS jsonb))",
                projectId, envId, "[\"SHADOW_TABLE\"]");
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
        jdbcTemplate.update("DELETE FROM tp_perf_sample WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFJMTR%')");
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFJMTR%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFJMTR%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFJMTR%')");
        jdbcTemplate.update("DELETE FROM tp_perf_approval WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFJMTR%')");
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFJMTR%')");
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFJMTR%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFJMTR%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFJMTR%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PFJMTR%'");
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
                "jmeter disclosure report seed managed by perf report-jmeter-disclosure test"));
    }

    private long newScenario(String engine) {
        long scenarioId = perfScenarioService.create(projectId, new PerfScenarioInput(
                "JMeter口径披露场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 30, "durationSeconds", 60),
                null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "https://perf-jmeter-disclosure.local/api/orders")),
                null, null));
        if (engine != null) {
            jdbcTemplate.update("UPDATE tp_perf_scenario SET engine = ? WHERE id = ?",
                    engine, scenarioId);
        }
        return scenarioId;
    }

    /** Pin a run over the scenario with launch's intake row shape (status
     *  running — recordFrames rejects frames for finished runs — JSONB
     *  pressure_snapshot) without crossing the executor-dispatch capability
     *  gate; see class javadoc fixture posture. */
    private long seedRun(long scenarioId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_perf_run (scenario_id, project_id, status, pressure_snapshot) "
                        + "VALUES (?, ?, 1, CAST(? AS jsonb)) RETURNING id",
                Long.class, scenarioId, projectId,
                "{\"mode\":\"CONSTANT_RPS\",\"targetRps\":30,\"durationSeconds\":60}");
    }

    private String footnoteOf(long runId) {
        Map<String, Object> report = perfReportService.build(projectId, runId);
        return String.valueOf(report.get("caliberFootnote"));
    }

    /** JMETER spectrum: the report footnote carries the approximation caliber
     *  verbatim from the JMX single source (wizard annotation shares it). */
    @Test
    void jmeterRunFootnoteCarriesApproximationCaliber() {
        long runId = seedRun(newScenario("JMETER"));
        perfMetricService.recordFrames(projectId, runId, List.of(
                new PerfMetricFrame(0, 40.0, 120.0, 0.02, null, null, null, null, null)));

        String footnote = footnoteOf(runId);
        assertTrue(footnote.contains(JmxTemplate.APPROXIMATION_NOTE),
                "JMETER 报告注脚必须程序化嵌入单源近似口径: " + footnote);
        assertTrue(footnote.contains("VU"),
                "注脚必须点名 VU 驱动近似口径: " + footnote);
    }

    /** Control spectrum: exact-schedule engines stay disclosure-clean. */
    @Test
    void defaultEngineFootnoteStaysFreeOfJmeterWording() {
        long runId = seedRun(newScenario(null));
        perfMetricService.recordFrames(projectId, runId, List.of(
                new PerfMetricFrame(0, 40.0, 120.0, 0.02, null, null, null, null, null)));

        String footnote = footnoteOf(runId);
        assertFalse(footnote.contains("Precise Throughput Timer"),
                "非 JMeter 引擎注脚禁出现速率近似披露噪音: " + footnote);
    }

    /** Launch-pinned truth (A6 dual-source fix): a scenario whose engine column
     *  stays blank is dispatched through the K8 default-engine key, and the
     *  launch transaction freezes that resolved engine into the run's pressure
     *  snapshot. The report face must read the pinned engine first — re-deriving
     *  it from the scenario row would disagree with what actually pressed. */
    @Test
    void snapshotPinnedEngineDrivesDisclosureWhenScenarioEngineBlank() {
        long runId = seedRunPinnedEngine(newScenario(null), "JMETER");
        perfMetricService.recordFrames(projectId, runId, List.of(
                new PerfMetricFrame(0, 40.0, 120.0, 0.02, null, null, null, null, null)));

        String footnote = footnoteOf(runId);
        assertTrue(footnote.contains(JmxTemplate.APPROXIMATION_NOTE),
                "运行快照钉定的 JMETER 引擎必须驱动报告披露（场景列为空=派发走缺省键）: " + footnote);
    }

    /** Legacy preservation: runs pinned before the engine key existed in the
     *  snapshot fall back to the scenario engine honestly (no disclosure loss
     *  for historical reports, no fabricated snapshot value). */
    @Test
    void legacyRunWithoutSnapshotEngineFallsBackToScenarioEngine() {
        long runId = seedRun(newScenario("JMETER"));
        perfMetricService.recordFrames(projectId, runId, List.of(
                new PerfMetricFrame(0, 40.0, 120.0, 0.02, null, null, null, null, null)));

        String footnote = footnoteOf(runId);
        assertTrue(footnote.contains(JmxTemplate.APPROXIMATION_NOTE),
                "无快照引擎键的历史运行必须诚实回退场景引擎: " + footnote);
    }

    /** Pin a run whose snapshot carries the resolved engine (the launch pin's
     *  post-fix shape) while the scenario column stays blank. */
    private long seedRunPinnedEngine(long scenarioId, String engine) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_perf_run (scenario_id, project_id, status, pressure_snapshot) "
                        + "VALUES (?, ?, 1, CAST(? AS jsonb)) RETURNING id",
                Long.class, scenarioId, projectId,
                "{\"mode\":\"CONSTANT_RPS\",\"targetRps\":30,\"durationSeconds\":60,"
                        + "\"engine\":\"" + engine + "\"}");
    }
}
