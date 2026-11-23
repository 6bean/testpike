package com.sixbean.testpike.core.program.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.program.ProgramLensFixture;
import com.sixbean.testpike.core.program.ProgramLensFixtureExtension;
import com.sixbean.testpike.core.program.domain.service.ProgramService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deleting a program lens must refuse while journey plans still reference it.
 *
 * <p>The journey table holds a foreign key onto tp_program, so a blind delete
 * surfaced as a raw constraint violation (HTTP 50000 "system busy") instead of
 * an actionable business refusal. The golden journey E2E cleanup proved this end
 * to end; these cases pin the service-layer contract: referenced delete is
 * refused with RESOURCE_CONFLICT naming the count, and the lens rows drop only
 * once no journey points at them.
 */
@SpringBootTest(classes = CoreApplication.class)
@ExtendWith(ProgramLensFixtureExtension.class)
class ProgramDeleteReferenceTest {

    @Autowired
    private ProgramService programService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Inserts one journey row pointing at the program and returns its id. */
    private long seedJourney(long programId) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_journey (program_id, name, owner_user_id, status, created_by) "
                        + "VALUES (?, ?, ?, 1, ?) RETURNING id",
                Long.class, programId, "delete-reference-probe-" + System.nanoTime(),
                ProgramLensFixture.OPERATOR, ProgramLensFixture.OPERATOR);
        return id;
    }

    private void purgeJourney(long journeyId) {
        // Segments/runs hang off the journey row; clear children first by id (R37: no TRUNCATE).
        jdbcTemplate.update("DELETE FROM tp_journey_segment WHERE journey_id = ?", journeyId);
        jdbcTemplate.update("DELETE FROM tp_journey_run WHERE journey_id = ?", journeyId);
        jdbcTemplate.update("DELETE FROM tp_journey WHERE id = ?", journeyId);
    }

    private int programRows(long programId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_program WHERE id = ?", Integer.class, programId);
        return n == null ? 0 : n;
    }

    @Test
    void delete_isRefusedWithBusinessCodeWhileJourneysReferenceTheProgram(ProgramLensFixture fixture) {
        long programId = fixture.newProgram();
        long journeyId = seedJourney(programId);
        try {
            ServiceException refusal = assertThrows(ServiceException.class,
                    () -> programService.delete(programId, ProgramLensFixture.OPERATOR));

            assertEquals(BizErrorCode.RESOURCE_CONFLICT, refusal.getCode(),
                    "referenced delete must answer with the conflict code, not a generic 500");
            assertTrue(refusal.getMessage().contains("1"),
                    "the refusal names how many journeys block the delete: " + refusal.getMessage());
            assertEquals(1, programRows(programId), "the lens row survives the refused delete");
        } finally {
            purgeJourney(journeyId);
        }
    }

    @Test
    void delete_dropsLensRowsOnceNoJourneyReferencesThem(ProgramLensFixture fixture) {
        long programId = fixture.newProgram();
        long journeyId = seedJourney(programId);
        purgeJourney(journeyId);

        programService.delete(programId, ProgramLensFixture.OPERATOR);

        assertEquals(0, programRows(programId), "the program row drops after the reference is gone");
        List<String> leftovers = jdbcTemplate.queryForList(
                "SELECT 'tp_user_program' FROM tp_user_program WHERE program_id = ? "
                        + "UNION ALL SELECT 'tp_program_project' FROM tp_program_project WHERE program_id = ?",
                String.class, programId, programId);
        assertTrue(leftovers.isEmpty(), "membership and grant rows of the lens are removed with it");
    }
}
