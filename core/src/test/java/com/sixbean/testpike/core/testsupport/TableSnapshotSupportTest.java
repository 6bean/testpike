package com.sixbean.testpike.core.testsupport;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the shared snapshot-restore helper: replay must return
 * the captured rows with original ids, per-test clear must not damage the
 * backups, and no scratch backup table may survive a restored window.
 */
@SpringBootTest(classes = CoreApplication.class)
class TableSnapshotSupportTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String SRC = "tmp_table_snapshot_src";

    @AfterEach
    void dropScratchTables() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + SRC);
        jdbcTemplate.execute("DROP TABLE IF EXISTS backup_of_" + SRC);
    }

    @Test
    void restoreReplaysCapturedRowsWithOriginalIds() {
        createScratchTable();
        jdbcTemplate.update("INSERT INTO " + SRC + " (note) VALUES ('a'), ('b')");
        long originalMaxId = scalar("SELECT max(id) FROM " + SRC);

        TableSnapshotSupport tables = new TableSnapshotSupport(jdbcTemplate, SRC);
        tables.snapshotAndClear();
        assertEquals(0, count(), "snapshotAndClear must leave the live table empty");

        jdbcTemplate.update("INSERT INTO " + SRC + " (note) VALUES ('polluted')");
        assertEquals(1, count());

        tables.restore();
        assertEquals(2, count(), "replay must bring back exactly the captured rows");
        assertEquals("a,b", jdbcTemplate.queryForObject(
                "SELECT string_agg(note, ',' ORDER BY id) FROM " + SRC, String.class));
        assertEquals(originalMaxId, scalar("SELECT max(id) FROM " + SRC),
                "replayed rows keep their original ids");
        assertEquals(0, backupExists(), "backup scratch table must be dropped after restore");
    }

    @Test
    void clearKeepsBackupsForRepeatedFreshStarts() {
        createScratchTable();
        jdbcTemplate.update("INSERT INTO " + SRC + " (note) VALUES ('seed')");
        TableSnapshotSupport tables = new TableSnapshotSupport(jdbcTemplate, SRC);
        tables.snapshotAndClear();

        jdbcTemplate.update("INSERT INTO " + SRC + " (note) VALUES ('run-1')");
        tables.clear();
        assertEquals(0, count(), "per-test clear empties the live table again");

        jdbcTemplate.update("INSERT INTO " + SRC + " (note) VALUES ('run-2')");
        tables.restore();
        assertEquals(1, count(), "restore replays only the captured snapshot");
        assertEquals("seed", jdbcTemplate.queryForObject(
                "SELECT note FROM " + SRC + " ORDER BY id LIMIT 1", String.class));
    }

    private void createScratchTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + SRC);
        jdbcTemplate.execute("CREATE TABLE " + SRC
                + " (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, note TEXT NOT NULL)");
    }

    private long count() {
        return scalar("SELECT count(*) FROM " + SRC);
    }

    private long backupExists() {
        return scalar("SELECT count(*) FROM information_schema.tables"
                + " WHERE table_name = 'backup_of_" + SRC + "'");
    }

    private long scalar(String sql) {
        Long v = jdbcTemplate.queryForObject(sql, Long.class);
        assertTrue(v != null);
        return v;
    }
}
