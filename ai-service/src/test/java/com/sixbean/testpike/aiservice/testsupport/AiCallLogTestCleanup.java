package com.sixbean.testpike.aiservice.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared purge helper for tp_ai_call_log rows written by LLM-facing tests.
 *
 * <p>Single source (R40) for the DELETE statement and the test project-range
 * convention: each test allocates a PROJECT_BASE block and cleans every call
 * log row inside [base, base + span) after each case, so a run never leaves
 * observability rows behind. Legacy copies in other packages migrate here as
 * they are touched (ledger ticket in PROGRESS, ratchet only shrinks).
 */
public final class AiCallLogTestCleanup {

    private static final String DELETE_RANGE_SQL =
            "DELETE FROM tp_ai_call_log WHERE project_id >= ? AND project_id < ?";

    /** Width of the project-id block a single test class reserves. */
    public static final long RANGE_SPAN = 1000L;

    private AiCallLogTestCleanup() {
    }

    /**
     * Deletes all call-log rows of the reserved project block of one test class.
     *
     * @param jdbcTemplate  live test-context JDBC template against the real schema
     * @param projectBase  first project id of the reserved block
     */
    public static void purgeProjectRange(JdbcTemplate jdbcTemplate, long projectBase) {
        jdbcTemplate.update(DELETE_RANGE_SQL, projectBase, projectBase + RANGE_SPAN);
    }
}
