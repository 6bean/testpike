package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.component.PerfMetricFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;

/**
 * Load-report export contract test .
 * <p>Authority = docs/16 row: PDF/HTML export rides the exact same report
 * assembly the online page renders (single source — export must never become a
 * second computation head), and the refusal wording names the accepted formats.
 * <p>Covers: ①pdf bytes start with the %PDF magic (real openhtmltopdf render via
 * the shared PdfHtmlRenderer, no mock); ②html carries run identity (scenario
 * name) + the metric card numbers + the programmatic caliber footnote;
 * ③unknown format is refused in human wording; ④a foreign-project run is refused
 * with the same NOT_FOUND wording as the online report (no existence leak).
 * <p>Runs against the real dev database (SpringBootTest, fixtures ride the same
 * posture as PerfReportServiceTest).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfReportExportTest {

    @Autowired
    private PerfReportService perfReportService;

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private PerfMetricService perfMetricService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;

    @BeforeEach
    void seedFixtures() {
        cleanupRows();
        // Fresh-install CI has no runner process online with an engine face,
        // so the perf launch gate would reject every run: seed one capable seat.
        SharedDbFixtureSweep.seedCapableExecutor(jdbcTemplate, "perf-export-ex-",
                "GATLING", "K6");
        long[] seeded = SharedDbFixtureSweep.seedPerfFixtureProject(jdbcTemplate,
                "压测导出测试项目_", "PERFEXP", "性能-导出测", 1,
                "http://perf-target.local/api", 1000, null);
        projectId = seeded[0];
        envId = seeded[1];
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
    }

    private void cleanupRows() {
        SharedDbFixtureSweep.executorsByNamePrefixes(jdbcTemplate, "perf-export-ex-");
        jdbcTemplate.update("DELETE FROM tp_perf_sample WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFEXP%')");
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFEXP%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFEXP%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFEXP%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFEXP%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFEXP%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PERFEXP%'");
    }

    private long lastId(String table) {
        return jdbcTemplate.queryForObject("SELECT id FROM " + table + " ORDER BY id DESC LIMIT 1",
                Long.class);
    }

    private long newScenario() {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                "导出契约场景_" + System.nanoTime(), envId, "CONSTANT_RPS",
                Map.of("targetRps", 50, "durationSeconds", 600), null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), null));
    }

    private long launchSeededRun() {
        long scenarioId = newScenario();
        long runId = perfRunService.launch(projectId, scenarioId, null);
        perfMetricService.recordFrames(projectId, runId, List.of(
                new PerfMetricFrame(0, 40.0, 120.0, 0.02, null, null, null, null, null),
                new PerfMetricFrame(1, 60.0, 200.0, 0.08, null, null, null, null, null)));
        return runId;
    }

    @Test
    void pdfExportStartsWithPdfMagic() {
        long runId = launchSeededRun();
        byte[] pdf = perfReportService.export(projectId, runId, "pdf");
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(1000);
    }

    @Test
    void htmlExportCarriesRunIdentityCardAndCaliber() {
        long runId = launchSeededRun();
        String scenarioName = String.valueOf(perfScenarioService
                .view(projectId, ((Number) jdbcTemplate.queryForObject(
                        "SELECT scenario_id FROM tp_perf_run WHERE id = ?", Long.class, runId)).longValue())
                .get("name"));
        byte[] html = perfReportService.export(projectId, runId, "html");
        String doc = new String(html, StandardCharsets.UTF_8);
        // run identity — the scenario this report belongs to
        assertThat(doc).contains(scenarioName);
        // metric card numbers come from the shared build() face, not a second head
        assertThat(doc).contains("60.0");
        // programmatic caliber footnote rides through
        assertThat(doc).contains("口径注脚");
    }

    @Test
    void unknownFormatRefusedNamingAcceptedFormats() {
        long runId = launchSeededRun();
        assertThatThrownBy(() -> perfReportService.export(projectId, runId, "docx"))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(BizErrorCode.VALIDATION_ERROR);
                    assertThat(e.getMessage()).contains("pdf").contains("html");
                });
    }

    @Test
    void foreignProjectRunRefusedWithoutExistenceLeak() {
        long runId = launchSeededRun();
        long otherProject = lastId("tp_project") + 987654321L;
        assertThatThrownBy(() -> perfReportService.export(otherProject, runId, "pdf"))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(BizErrorCode.NOT_FOUND);
                    // the refusal is the same wording as the online report face
                    assertThat(e.getMessage()).contains("不存在或不属于该项目")
                            .contains("perf.run.retention-days");
                });
    }
}
