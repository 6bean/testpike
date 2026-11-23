package com.sixbean.testpike.core.support;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared watermark bookkeeping for cluster-command fixture suites.
 *
 * <p>Snapshots the id floors of {@code tp_cluster_command} and
 * {@code tp_cluster_enroll_token}, purges stale executor rows by name prefix,
 * and sweeps only rows created by the running suite so suites that share the
 * database never delete each other's fixtures. One implementation for both
 * the audit-trail suite and the receipt-auth suite (R40 single source).
 */
public final class ClusterCommandFixtureLedger {

    private final JdbcTemplate jdbcTemplate;
    private final String executorNamePrefix;

    private long commandFloor;
    private long tokenFloor;

    public ClusterCommandFixtureLedger(JdbcTemplate jdbcTemplate, String executorNamePrefix) {
        this.jdbcTemplate = jdbcTemplate;
        this.executorNamePrefix = executorNamePrefix;
    }

    /** Remembers the id floors and removes leftover executor rows of this suite. */
    public void markWatermarks() {
        commandFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_cluster_command");
        tokenFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_cluster_enroll_token");
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE ?", executorNamePrefix + "%");
    }

    /** Deletes command/token rows created after the watermark plus tracked executors. */
    public void sweepRunRows(List<Long> trackedExecutors) {
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_cluster_command", commandFloor);
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_cluster_enroll_token", tokenFloor);
        SharedDbFixtureSweep.byIds(jdbcTemplate, "tp_executor", trackedExecutors);
        trackedExecutors.clear();
    }
}
