package com.sixbean.testpike.core.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Snapshot-and-restore helper for factory tables a test must temporarily
 * empty. Required shape for empty-state tests on the shared development
 * database and on fresh-install CI databases: back the tables up, clear
 * them, exercise the empty state, then replay the byte-identical original
 * rows so the suite leaves factory data untouched.
 *
 * <p>Why not a bare TRUNCATE: factory seed rows are consumed by every
 * later test class (audit hash-chain probes, seed visibility assertions).
 * Wiping them without replay makes the whole suite order-dependent and
 * turns a fresh-install run red. A multi-table TRUNCATE inside this helper
 * is deliberate: row-level append-only triggers never fire on TRUNCATE,
 * which is exactly what the empty-state scenario needs, while restore()
 * guarantees the tables come back exactly as captured.
 *
 * <p>Preconditions: every listed table must be truncable in one statement
 * (no inbound foreign keys from tables outside the list; list parents
 * before children for the replay order), and each id column must be
 * GENERATED ALWAYS AS IDENTITY because replay inserts with OVERRIDING
 * SYSTEM VALUE to keep original ids byte-identical.
 */
public final class TableSnapshotSupport {

    private final JdbcTemplate jdbc;
    private final List<String> tables;

    public TableSnapshotSupport(JdbcTemplate jdbc, String... tables) {
        this.jdbc = jdbc;
        this.tables = List.of(tables);
    }

    /** Capture current rows into scratch backups, then empty the tables. */
    public void snapshotAndClear() {
        for (String table : tables) {
            jdbc.execute("DROP TABLE IF EXISTS " + backupName(table));
            jdbc.execute("CREATE TABLE " + backupName(table) + " AS SELECT * FROM " + table);
        }
        clear();
    }

    /**
     * Empty the live tables again without touching the backups, so each
     * single test starts from a clean state inside one snapshot window.
     */
    public void clear() {
        jdbc.execute("TRUNCATE " + String.join(", ", tables));
    }

    /**
     * Drop everything produced inside the window, replay the exact captured
     * rows (original ids included), then remove the backups.
     */
    public void restore() {
        clear();
        for (String table : tables) {
            jdbc.update("INSERT INTO " + table
                    + " OVERRIDING SYSTEM VALUE SELECT * FROM " + backupName(table));
            jdbc.execute("DROP TABLE IF EXISTS " + backupName(table));
        }
    }

    private String backupName(String table) {
        return "backup_of_" + table;
    }
}
