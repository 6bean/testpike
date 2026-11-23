package com.sixbean.testpike.core.reportcenter.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.program.ProgramLensFixture;
import com.sixbean.testpike.core.program.ProgramLensFixtureExtension;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportGenerateInput;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportListItem;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportQuery;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Program-dimension report snapshots : tp_report scope_type=3 means
 * "one program (project set)" and its payload aggregates exactly the projects attached
 * to that program — no outsider project rows, no global rows, no second caliber SQL.
 *
 * <p>Contracts under test: value domain widened to {1,2,3}; a program row keeps the
 * program id in scope_id (never NULL, V53 ruling inherited); regeneration of the same
 * (scope_type, scope_id, report_type, period_start) overwrites one row; unknown or
 * missing program ids fail honestly; the pass-rate caliber block of a program report
 * counts only lens projects while a single project outside the lens keeps its own
 * (diluted) value.
 *
 * <p>Lens/project fixture and teardown come from ProgramLensFixture (single source,
 * R40); snapshots are produced through the real generate channel against the chain
 * slot database. Shipped tables are never truncated; seeded tasks are deleted by id.
 */
@SpringBootTest(classes = CoreApplication.class)
@ExtendWith(ProgramLensFixtureExtension.class)
class ReportProgramScopeSnapshotTest {

    private static final int SCOPE_PROGRAM = 3;

    @Autowired
    private ReportCenterService reportCenterService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    /** Current week [Monday 00:00, next Monday 00:00) — start is never in the future. */
    private static String[] thisWeek() {
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new String[]{monday.atStartOfDay().toString(),
                monday.plusDays(7).atStartOfDay().toString()};
    }

    /** One terminal task (with its case) inside the current week window; returns the task id. */
    private long seedTask(long projectId, int status) {
        long caseId = caseService.create(ProgramLensFixture.OPERATOR,
                ProgramLensFixture.caseInput(projectId, "集维快照用例-" + status + "-" + System.nanoTime())).id();
        Long id = jdbc.queryForObject(
                "INSERT INTO tp_exec_task (project_id, case_id, status, schema_version, action_json)"
                        + " VALUES (?, ?, ?, 'v2', '{}') RETURNING id",
                Long.class, projectId, caseId, status);
        assertNotNull(id);
        return id;
    }

    private JsonNode payloadOf(ReportView view) throws Exception {
        return objectMapper.readTree(view.payloadJson());
    }

    @Test
    void programSnapshotCarriesProgramScopeAndFullCaliberRegistry(ProgramLensFixture fixture) throws Exception {
        long[] s = fixture.lensOverTwoOfThreeProjects();
        String[] w = thisWeek();
        ReportView view = reportCenterService.generate(ProgramLensFixture.OPERATOR,
                new ReportGenerateInput(1, SCOPE_PROGRAM, s[3], w[0], w[1]));

        assertEquals(SCOPE_PROGRAM, view.scopeType());
        assertEquals(s[3], view.scopeId());
        JsonNode payload = payloadOf(view);
        assertEquals("program", payload.path("scope").path("type").asText());
        assertEquals(s[3], payload.path("scope").path("id").asLong());
        JsonNode calibers = payload.path("calibers");
        assertEquals(8, calibers.size(), "every snapshot caliber gets a block");
        assertTrue(calibers.path("pass-rate").has("display_name"));
        assertTrue(calibers.path("pass-rate").has("version"));
    }

    @Test
    void programPassRateCountsOnlyItsOwnProjects(ProgramLensFixture fixture) throws Exception {
        long[] s = fixture.lensOverTwoOfThreeProjects();
        List<Long> seeded = new ArrayList<>();
        try {
            seeded.add(seedTask(s[0], 3));
            seeded.add(seedTask(s[1], 3));
            seeded.add(seedTask(s[2], 4));   // outside project: a failure that must not dilute

            String[] w = thisWeek();
            ReportView program = reportCenterService.generate(ProgramLensFixture.OPERATOR,
                    new ReportGenerateInput(1, SCOPE_PROGRAM, s[3], w[0], w[1]));
            double inLens = payloadOf(program).path("calibers").path("pass-rate").path("value").asDouble();
            assertEquals(1.0, inLens, 1e-9, "2 passes in lens projects, outsider failure excluded");

            ReportView outsider = reportCenterService.generate(ProgramLensFixture.OPERATOR,
                    new ReportGenerateInput(1, 1, s[2], w[0], w[1]));
            double outside = payloadOf(outsider).path("calibers").path("pass-rate").path("value").asDouble();
            assertEquals(0.0, outside, 1e-9, "outsider keeps its own diluted value");
        } finally {
            seeded.forEach(id -> jdbc.update("DELETE FROM tp_exec_task WHERE id = ?", id));
        }
    }

    @Test
    void programSnapshotRejectsMissingScopeIdAndUnknownProgram() {
        String[] w = thisWeek();
        ServiceException missing = assertThrows(ServiceException.class,
                () -> reportCenterService.generate(ProgramLensFixture.OPERATOR,
                        new ReportGenerateInput(1, SCOPE_PROGRAM, null, w[0], w[1])));
        assertTrue(missing.getMessage().contains("scopeId"), missing.getMessage());

        assertThrows(ServiceException.class,
                () -> reportCenterService.generate(ProgramLensFixture.OPERATOR,
                        new ReportGenerateInput(1, SCOPE_PROGRAM, 999999999L, w[0], w[1])));
    }

    @Test
    void programSnapshotRegenerateOverwritesSameRow(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreeProjects();
        String[] w = thisWeek();
        long first = reportCenterService.generate(ProgramLensFixture.OPERATOR,
                new ReportGenerateInput(1, SCOPE_PROGRAM, s[3], w[0], w[1])).id();
        long again = reportCenterService.generate(ProgramLensFixture.OPERATOR,
                new ReportGenerateInput(1, SCOPE_PROGRAM, s[3], w[0], w[1])).id();
        assertEquals(first, again, "idempotent ON CONFLICT keeps one row per program period");
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM tp_report"
                + " WHERE scope_type = 3 AND scope_id = ? AND report_type = 1 AND period_start = ?::timestamp",
                Integer.class, s[3], w[0]);
        assertEquals(1, rows);
    }

    @Test
    void listFilterAcceptsProgramScopeTypeAndRejectsOutOfRange(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreeProjects();
        String[] w = thisWeek();
        reportCenterService.generate(ProgramLensFixture.OPERATOR,
                new ReportGenerateInput(1, SCOPE_PROGRAM, s[3], w[0], w[1]));

        PageResult<ReportListItem> got = reportCenterService.list(
                new ReportQuery(1, 50, null, SCOPE_PROGRAM, null, null, null, null, null, 0L),
                ProgramLensFixture.OPERATOR);
        assertTrue(got.list().stream().allMatch(r -> r.scopeType() == SCOPE_PROGRAM));
        assertTrue(got.list().stream().anyMatch(r -> r.scopeId() == s[3]));

        assertThrows(ServiceException.class, () -> reportCenterService.list(
                new ReportQuery(1, 50, null, 4, null, null, null, null, null, 0L),
                ProgramLensFixture.OPERATOR));
    }
}
