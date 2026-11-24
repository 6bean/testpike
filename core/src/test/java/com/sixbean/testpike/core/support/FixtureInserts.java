package com.sixbean.testpike.core.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Insert helper for test fixtures that need the identity of the row they just
 * created.
 *
 * <p>Fixtures previously ran {@code SELECT max(id)} after their INSERT to
 * recover the new row id. On a fresh-install database other fixtures may have
 * planted rows with explicitly overridden (very large) ids, so {@code max(id)}
 * returns a foreign row instead of the fixture's own. The fixture then writes
 * dependent rows under the wrong parent and collides with unique constraints
 * such as {@code uq_project_env_type}. Reading the id back with RETURNING
 * binds each fixture to its own row and removes the ordering coupling.
 */
public final class FixtureInserts {

    private FixtureInserts() {
    }

    /**
     * Executes a single-row INSERT and returns the generated id.
     *
     * @param jdbcTemplate live template bound to the test database
     * @param insertSql    complete INSERT statement without the RETURNING clause
     * @param args         positional bind parameters for the statement
     * @return the id column of the inserted row
     */
    public static long insertReturningId(JdbcTemplate jdbcTemplate, String insertSql, Object... args) {
        Long id = jdbcTemplate.queryForObject(insertSql + " RETURNING id", Long.class, args);
        if (id == null) {
            throw new IllegalStateException("INSERT RETURNING produced no id for statement: " + insertSql);
        }
        return id;
    }
}
