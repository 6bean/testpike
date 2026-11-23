package com.sixbean.testpike.core.appauto.domain.service;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared fixture teardown for appauto suites that register executors under a
 * fixed name family (single source per R40 - the two consuming suites call it
 * with their own prefixes and must not carry duplicated SQL blocks).
 *
 * <p>Replaces the former blanket {@code TRUNCATE tp_exec_task, tp_executor
 * CASCADE}, which wiped the resident chain runner row and other suites' data
 * on the shared dev database .
 */
final class AppautoExecutorFixture {

    private AppautoExecutorFixture() {
    }

    /** Removes exec-task children first, then the executor rows of the family. */
    static void deleteExecutorFamily(JdbcTemplate jdbcTemplate, String namePrefix) {
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE executor_id IN"
                        + " (SELECT id FROM tp_executor WHERE name LIKE ?)",
                namePrefix + "%");
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE ?", namePrefix + "%");
    }
}
