package com.sixbean.testpike.core.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared fixture ledger for tests that create projects (single source for the
 * tracked-ids collection and the sweep ritual; new fixtures consume this instead
 * of re-copying the same before/after block — R40).
 */
public final class TrackedProjectLedger {

    private final List<Long> projectIds = new CopyOnWriteArrayList<>();

    /** Ids accumulated during the test, consumed by ProjectFixtureRig factory calls. */
    public List<Long> projectIds() {
        return projectIds;
    }

    /** Remove every project (and its cascading fixture rows) created through this ledger. */
    public void sweep(JdbcTemplate jdbcTemplate) {
        ProjectFixtureRig.sweep(jdbcTemplate, projectIds);
    }
}
