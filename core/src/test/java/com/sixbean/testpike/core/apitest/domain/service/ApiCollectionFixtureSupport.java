package com.sixbean.testpike.core.apitest.domain.service;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Row-scoped teardown shared by the collection service test family (single source, R40).
 *
 * <p>Removes only the rows owned by fixture projects (unique code prefixes W1 / ).
 * Shipped tables are never truncated (R37②): the former TRUNCATE ... CASCADE wiped
 * seed anchors (tp_user OPERATOR, shipped tp_tool/tp_skill rows) on every run and
 * made the slot baseline ledger flicker red for any window touching these classes.
 */
public final class ApiCollectionFixtureSupport {

    private static final String OWN_FIXTURE_PROJECTS =
            "SELECT id FROM tp_project WHERE code LIKE 'W1%' OR code LIKE 'T412%'";

    private ApiCollectionFixtureSupport() {
    }

    /** Delete fixture requests, collections, memberships and projects — in FK order. */
    public static void cleanFixtureRows(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM tp_apitest_request WHERE collection_id IN ("
                + "SELECT id FROM tp_apitest_collection WHERE project_id IN ("
                + OWN_FIXTURE_PROJECTS + "))");
        jdbc.update("DELETE FROM tp_apitest_collection WHERE project_id IN ("
                + OWN_FIXTURE_PROJECTS + ")");
        jdbc.update("DELETE FROM tp_user_project WHERE project_id IN (" + OWN_FIXTURE_PROJECTS + ")");
        jdbc.update("DELETE FROM tp_project WHERE code LIKE 'W1%' OR code LIKE 'T412%'");
    }
}
