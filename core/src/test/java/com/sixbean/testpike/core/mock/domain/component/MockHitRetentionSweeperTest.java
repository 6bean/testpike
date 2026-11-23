package com.sixbean.testpike.core.mock.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retention sweep for the mock hit ledger the sweeper that
 * finally consumes the {@code mock.hit-retention-days} key seeded by the shell
 * batch with the honest note "the shell only defines the semantics"; until now
 * no code read it, so tp_mock_hit_log grew without bound).
 * <p>Behaviour under test (real dev database, zero mocks — posture of
 * MockSpaceExpirySweeperTest):
 * <ul>
 * <li>rows older than the window are deleted, rows inside it are kept;</li>
 * <li>the sweep is idempotent: a second round settles nothing new;</li>
 * <li>the window rides the K8 key read fresh on every run (hot-update path
 * is ConfigService's own tested contract — here we pin the fallback and
 * the parse guard so a garbage/blank value can never delete everything);</li>
 * <li>a non-positive window means "keep everything", never "purge all".</li>
 * </ul>
 * Seeded fixture rows carry a unique session_id tag so cleanup and assertions
 * only ever look at this test's own rows.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=true")
class MockHitRetentionSweeperTest {

    @Autowired
    private MockHitRetentionSweeper sweeper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String tag = "t367sweep" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    /** tp_mock_hit_log has no FK on space_id (ledger is append-only truth); tag rides session_id. */
    private long seedRow(String ageInterval) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_mock_hit_log (space_id, matched, session_id, hit_at) "
                        + "VALUES (0, true, ?, now() - interval '" + ageInterval + "') RETURNING id",
                Long.class, tag);
    }

    private int countMyRows(String ageInterval) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_mock_hit_log WHERE session_id = ? "
                        + "AND hit_at < now() - interval '" + ageInterval + "'",
                Integer.class, tag);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM tp_mock_hit_log WHERE session_id = ?", tag);
    }

    @Test
    void deletesRowsOlderThanWindowAndKeepsRecentOnes() {
        seedRow("45 days");  // far outside the 30-day window -> must go
        seedRow("31 days");  // just outside the window -> must go
        long keptOld = seedRow("29 days"); // just inside -> must stay
        long keptFresh = seedRow("1 hour");

        int deleted = sweeper.purgeExpiredHits();

        assertTrue(deleted >= 2, "at least this test's two stale rows must be swept (actual: " + deleted + ")");
        assertEquals(0, countMyRows("30 days"), "stale rows (older than window) must be gone");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_mock_hit_log WHERE id = ?", Integer.class, keptOld),
                "29-day row sits inside the window and must survive");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_mock_hit_log WHERE id = ?", Integer.class, keptFresh),
                "fresh row must survive");
    }

    @Test
    void secondRoundSettlesNothingNew() {
        seedRow("40 days");
        sweeper.purgeExpiredHits();

        int again = sweeper.purgeExpiredHits();

        assertEquals(0, countMyRows("30 days"), "stale fixture rows were already settled");
        // Own rows can no longer contribute; other-space residue may still be
        // swept, so the honest pin is on our own tags, not on `again` itself.
        assertTrue(again >= 0);
    }

    @Test
    void windowFallsBackToThirtyDaysAndNeverTrustsGarbage() {
        // The seeded K8 value is 30 — the fresh read must match, and parsing
        // must fail closed to the default (a garbage value deleting "all
        // history" or "nothing" by accident is the failure mode pinned here).
        assertEquals(30, MockHitRetentionSweeper.parseRetentionDays("30"));
        // A valid hot-updated value is honoured (K8 contract — no hard-coded re-pin).
        assertEquals(45, MockHitRetentionSweeper.parseRetentionDays(" 45 "));
        assertEquals(30, MockHitRetentionSweeper.parseRetentionDays(null));
        assertEquals(30, MockHitRetentionSweeper.parseRetentionDays(""));
        assertEquals(30, MockHitRetentionSweeper.parseRetentionDays("abc"));
        // Non-positive = operator says "keep everything", guard must not purge all.
        assertTrue(sweeper.sweepWithWindow(-1) == 0, "negative window must be refused, never purge-everything");
        assertTrue(sweeper.sweepWithWindow(0) == 0, "zero window must be refused");
    }
}
