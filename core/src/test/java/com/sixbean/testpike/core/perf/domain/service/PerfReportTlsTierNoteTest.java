package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.component.PerfMetricFrame;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;

/**
 * B-10 report-face TLS tier note .
 * <p>Gap this test anchors: the launch already pins the environment's TLS tier
 * into the run snapshot , but the assembled report exposed no tier
 * row — a reader of the report could not tell whether the numbers were pressed
 * against a strictly verified endpoint or an insecure one, and the frontend
 * yellow hint had no single-source key to ride. The fix follows the dslHashOf
 * read posture: {@code build} reads the pinned snapshot and ships
 * {@code tlsTier} (strict / custom_ca / insecure); a legacy run whose snapshot
 * predates the key ships null — the honest absence, never a fabricated
 * "strict" label.
 * <p>Red anchors: custom_ca environment run reports "custom_ca"; an
 * environment without a tls section reports "strict" (fail-safe default);
 * a snapshot with the tlsMode key stripped (legacy row) reports null.
 * <p>Real chain, no mocks: dev DB fixtures + service calls (fixture posture
 * follows PerfRunTlsPinningTest).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfReportTlsTierNoteTest {

    private static final String TEST_CA_PEM =
            "-----BEGIN CERTIFICATE-----\nTEST-CA-MATERIAL\n-----END CERTIFICATE-----";

    @Autowired
    private PerfReportService perfReportService;

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

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
        // Fresh-install CI has no runner process online with an engine face,
        // so the perf launch gate would reject every run: seed one capable seat.
        SharedDbFixtureSweep.seedCapableExecutor(jdbcTemplate, "perf-rtls-ex-",
                "GATLING", "K6");
        long[] seeded = SharedDbFixtureSweep.seedPerfFixtureProject(jdbcTemplate,
                "TLS档位报告测试项目_", "PFRTLS", "测试-档位报告", 2,
                "https://perf-report-tls.local/api", 100, "[\"SHADOW_TABLE\"]");
        projectId = seeded[0];
        envId = seeded[1];
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
        SharedDbFixtureSweep.executorsByNamePrefixes(jdbcTemplate, "perf-rtls-ex-");
        jdbcTemplate.update("DELETE FROM tp_perf_sample WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFRTLS%')");
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFRTLS%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFRTLS%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFRTLS%')");
        jdbcTemplate.update("DELETE FROM tp_perf_approval WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFRTLS%')");
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFRTLS%')");
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFRTLS%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFRTLS%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFRTLS%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PFRTLS%'");
    }

    private void restoreSeed(String key, String value) {
        Long id = jdbcTemplate.query("SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, key);
        if (id != null) {
            configService.delete(null, id);
        }
        configService.create(null, new ConfigInput(key, value, "系统", "string", 0,
                "tls tier report seed managed by perf report-tls-tier test"));
    }

    /** Write a raw tls section onto the environment profile (plaintext PEM =
     *  the historical no-ENC pass-through the resolver tolerates by design). */
    private void setEnvTlsSection(String mode) {
        String configJson = mode == null ? "{}"
                : "{\"tls\":{\"mode\":\"" + mode + "\""
                        + (mode.equals("custom_ca")
                                ? ",\"ca_pem\":\"" + TEST_CA_PEM.replace("\n", "\\n") + "\"" : "")
                        + "}}";
        jdbcTemplate.update("UPDATE tp_project_env SET config_json = CAST(? AS jsonb) WHERE id = ?",
                configJson, envId);
    }

    private long newScenario() {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                "TLS档位报告场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 30, "durationSeconds", 60),
                null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "https://perf-report-tls.local/api/orders")),
                null, null));
    }

    /** Launch a run and seed one aggregate frame so the report builds on
     *  realistic input (empty-frame paths are covered elsewhere). */
    private long launchWithFrame() {
        long runId = perfRunService.launch(projectId, newScenario(), null);
        perfMetricService.recordFrames(projectId, runId, List.of(
                new PerfMetricFrame(0, 40.0, 120.0, 0.02, null, null, null, null, null)));
        return runId;
    }

    /** custom_ca spectrum: the report row states the tier the run was pinned
     *  with (the frontend yellow hint keys off insecure, this is the data). */
    @Test
    void reportShipsPinnedCustomCaTier() {
        setEnvTlsSection("custom_ca");
        long runId = launchWithFrame();

        Map<String, Object> report = perfReportService.build(projectId, runId);
        assertEquals("custom_ca", report.get("tlsTier"),
                "报告必须回显快照钉住的 TLS 档位: " + report.get("tlsTier"));
    }

    /** Fail-safe default: an environment without a tls section pins strict at
     *  launch, and the report states it — never blank, never a guess. */
    @Test
    void reportShipsStrictWhenEnvironmentHasNoTlsSection() {
        setEnvTlsSection(null);
        long runId = launchWithFrame();

        Map<String, Object> report = perfReportService.build(projectId, runId);
        assertEquals("strict", report.get("tlsTier"));
    }

    /** Honest absence: a legacy run row whose snapshot predates the pinned key
     *  yields a null tier — the report face must not fabricate "strict" for a
     *  run whose tier was never recorded. */
    @Test
    void legacyRunWithoutPinnedTierReportsNull() {
        setEnvTlsSection("insecure");
        long runId = launchWithFrame();
        int stripped = jdbcTemplate.update(
                "UPDATE tp_perf_run SET pressure_snapshot = pressure_snapshot - 'tlsMode' "
                        + "WHERE id = ?", runId);
        assertEquals(1, stripped, "夹具必须成功剥掉快照档位键（遗留行模拟）");

        Map<String, Object> report = perfReportService.build(projectId, runId);
        assertNull(report.get("tlsTier"), "遗留快照缺键必须诚实 null，禁冒充 strict");
        assertTrue(((Map<?, ?>) report.get("run")).containsKey("id"),
                "剥键不影响报告主体装配（零破坏）");
    }
}
