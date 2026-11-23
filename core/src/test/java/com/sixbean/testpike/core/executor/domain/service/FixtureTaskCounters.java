package com.sixbean.testpike.core.executor.domain.service;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fixture-scoped task counters for the executor claim suites (R40 single
 * source). On the shared dev database a global {@code count(*)} over
 * tp_exec_task would pick up rows of concurrent suites, so every counter
 * resolves the suite's fixed-name project family first and counts tasks
 * only inside that family. An empty family yields 0 (no rows match the
 * empty IN list), so a suite that swept everything reads a clean queue.
 */
final class FixtureTaskCounters {

    private FixtureTaskCounters() {
    }

    /** Task rows with the given status owned by projects named prefix-any of {@code names}. */
    static long countByStatus(JdbcTemplate jdbc, int status, List<String> projectNames) {
        if (projectNames.isEmpty()) {
            return 0L;
        }
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE status = ? AND project_id IN "
                        + familyProjectIds(projectNames),
                Long.class, status);
        return n == null ? 0L : n;
    }

    /** Task rows of any status owned by projects named prefix-any of {@code names}. */
    static long countInFamily(JdbcTemplate jdbc, List<String> projectNames) {
        if (projectNames.isEmpty()) {
            return 0L;
        }
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE project_id IN "
                        + familyProjectIds(projectNames),
                Long.class);
        return n == null ? 0L : n;
    }

    /** Shared id set behind both family counters (single source for the LIKE filter). */
    private static String familyProjectIds(List<String> projectNames) {
        String patterns = projectNames.stream()
                .map(n -> "'" + n + "%'")
                .collect(Collectors.joining(","));
        return "(SELECT id FROM tp_project WHERE name LIKE ANY(ARRAY[" + patterns + "]))";
    }
}
