package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Purge cascade for the program lens family (Y-wave program/journey tables).
 *
 * <p>Root cause locked here: tp_program_project carries a NO ACTION FK to
 * tp_project, so physically deleting a project that is attached to any program
 * died on tp_program_project_project_id_fkey with HTTP 500 (E2E golden journey
 * window proved the crash in the fixture-cleanup purge call).
 *
 * <p>The corrected verdict has two halves, both asserted below:
 * <ol>
 *   <li>the purge succeeds and every project-scoped row (bridge links, segment
 *       expansions, run-segment ledger rows) leaves with the project;</li>
 *   <li>program-owned rows survive untouched — the program lens, its member
 *       grants, the journeys and runs on it belong to the program, not to any
 *       single project (symmetric with ProgramService.detachProject never
 *       touching program rows, and with the shared-project link staying so a
 *       sibling project's view of the program is never damaged).</li>
 * </ol>
 *
 * <p>Real database, no mocks: seeds two scratch projects sharing one program.
 */
@SpringBootTest(classes = CoreApplication.class)
class ProjectPurgeProgramCascadeTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String code;
    private long purgedProjectId;
    private long siblingProjectId;
    private long programId;
    private long journeyId;
    private long runId;
    private long segmentP1;
    private long segmentP2;

    @BeforeEach
    void seedProgramLensFamily() {
        code = UUID.randomUUID().toString().replace("-", "");
        purgedProjectId = newProject("purge-prog-p1-" + code, "PRGFK1" + code.substring(0, 8));
        siblingProjectId = newProject("purge-prog-p2-" + code, "PRGFK2" + code.substring(0, 8));

        long userId = jdbcTemplate.queryForObject("SELECT min(id) FROM tp_user", Long.class);

        jdbcTemplate.update("INSERT INTO tp_program (name, description, created_by) VALUES (?, ?, ?)",
                "purge-prog-lens-" + code, "program lens cascade fixture", userId);
        programId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_program WHERE name = ?", Long.class, "purge-prog-lens-" + code);

        // Both projects share the program: purging one must detach only its link.
        for (long pid : new long[]{purgedProjectId, siblingProjectId}) {
            jdbcTemplate.update(
                    "INSERT INTO tp_program_project (program_id, project_id, created_by) VALUES (?, ?, ?)",
                    programId, pid, userId);
        }
        jdbcTemplate.update(
                "INSERT INTO tp_user_program (program_id, user_id, role, created_by) VALUES (?, ?, 1, ?)",
                programId, userId, userId);

        jdbcTemplate.update(
                "INSERT INTO tp_journey (program_id, name, owner_user_id) VALUES (?, ?, ?)",
                programId, "purge-prog-journey-" + code, userId);
        journeyId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_journey WHERE program_id = ? AND name = ?",
                Long.class, programId, "purge-prog-journey-" + code);

        jdbcTemplate.update("INSERT INTO tp_journey_run (journey_id, status, signed_by) "
                + "VALUES (?, 3, ?)", journeyId, userId);
        runId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_journey_run WHERE journey_id = ?", Long.class, journeyId);

        // One segment per project on the same journey (case target keeps the
        // seed free of extra asset FKs; case_id is a soft reference).
        segmentP1 = newSegment(journeyId, 1, purgedProjectId);
        segmentP2 = newSegment(journeyId, 2, siblingProjectId);
        for (long seg : new long[]{segmentP1, segmentP2}) {
            long segProject = seg == segmentP1 ? purgedProjectId : siblingProjectId;
            jdbcTemplate.update(
                    "INSERT INTO tp_journey_run_segment (run_id, segment_id, project_id, seq_no, status) "
                            + "VALUES (?, ?, ?, 1, 1)", runId, seg, segProject);
        }
    }

    private long newProject(String name, String projCode) {
        jdbcTemplate.update("INSERT INTO tp_project (name, code) VALUES (?, ?)", name, projCode);
        return jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, projCode);
    }

    private long newSegment(long journey, int seqNo, long project) {
        jdbcTemplate.update("INSERT INTO tp_journey_segment (journey_id, seq_no, project_id, "
                        + "target_kind, case_id) VALUES (?, ?, ?, 'case', 0)",
                journey, seqNo, project);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tp_journey_segment WHERE journey_id = ? AND seq_no = ?",
                Long.class, journey, seqNo);
    }

    @AfterEach
    void cleanFixture() {
        if (programId != 0) {
            jdbcTemplate.update("DELETE FROM tp_journey_run_segment WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM tp_journey_segment WHERE journey_id = ?", journeyId);
            jdbcTemplate.update("DELETE FROM tp_journey_run WHERE id = ?", runId);
            jdbcTemplate.update("DELETE FROM tp_journey WHERE id = ?", journeyId);
            jdbcTemplate.update("DELETE FROM tp_user_program WHERE program_id = ?", programId);
            jdbcTemplate.update("DELETE FROM tp_program_project WHERE program_id = ?", programId);
            jdbcTemplate.update("DELETE FROM tp_program WHERE id = ?", programId);
        }
        for (long pid : new long[]{purgedProjectId, siblingProjectId}) {
            if (pid != 0) {
                jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", pid);
            }
        }
    }

    @Test
    void purgeProjectAttachedToProgramDetachesBridgeAndKeepsProgramRows() {
        assertDoesNotThrow(() -> projectService.purge(1L, purgedProjectId, true),
                "purge force must detach the program bridge rows: the golden journey "
                        + "window proved the 500 on tp_program_project_project_id_fkey");

        assertEquals(0, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_program_project WHERE project_id = ?",
                        Long.class, purgedProjectId),
                "the purged project must leave no program-bridge rows");
        assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_program_project WHERE program_id = ? AND project_id = ?",
                        Long.class, programId, siblingProjectId),
                "the sibling project's link to the same program must survive");
        assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_program WHERE id = ?", Long.class, programId),
                "the program lens itself belongs to no single project and must stay");
        assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_user_program WHERE program_id = ?",
                        Long.class, programId),
                "program member grants are program-owned and must stay");
        assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_journey WHERE id = ?", Long.class, journeyId),
                "journey plans belong to the program and stay (same rule as the 6d comment)");
        assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_journey_run WHERE id = ?", Long.class, runId),
                "journey runs hang off the journey, not the project, and stay");

        assertEquals(0, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_journey_segment WHERE id = ?",
                        Long.class, segmentP1),
                "segments authored by the purged project leave with it");
        assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_journey_segment WHERE id = ?",
                        Long.class, segmentP2),
                "the sibling project's segment on the same journey must stay");
        assertEquals(0, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_journey_run_segment WHERE project_id = ?",
                        Long.class, purgedProjectId),
                "run-segment expansion rows of the purged project leave with it");
        assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_journey_run_segment WHERE project_id = ?",
                        Long.class, siblingProjectId),
                "the sibling project's expansion rows must stay");
    }
}
