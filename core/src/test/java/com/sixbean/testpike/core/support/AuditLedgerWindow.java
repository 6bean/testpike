package com.sixbean.testpike.core.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Ledger-window bookkeeping for suites writing into the append-only
 * tp_audit_log (R37 discipline, R40 single source: both audit suites consume
 * this one window helper).
 *
 * <p>A window captures the table's max id before the test body runs and, on
 * teardown, sweeps exactly the rows that appeared above that watermark. The
 * sweep is run-owned (it never touches shipped or historical rows) and stays
 * chain-safe because the deletion routes through the transaction-local
 * retention GUC channel shared with {@link SharedDbFixtureSweep#deleteSince}.
 */
public final class AuditLedgerWindow {

    private final JdbcTemplate jdbc;
    private long floor;

    public AuditLedgerWindow(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Capture the watermark before this run's audit rows are written. */
    public void open() {
        floor = SharedDbFixtureSweep.maxId(jdbc, "tp_audit_log");
    }

    /** Sweep exactly this window's rows; an empty window is a no-op. */
    public void close() {
        SharedDbFixtureSweep.deleteSince(jdbc, "tp_audit_log", floor);
    }

    /** Lower bound of this window: rows with id &gt; floor belong to this run. */
    public long floor() {
        return floor;
    }
}
