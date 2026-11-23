package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolScaleEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Autoscaler scale-event ledger read contract: the newest event epoch per pool feeds the
 * cooldown guardrail, and an un-scaled pool reads 0 so the guard degrades to
 * "never recently scaled" instead of suppressing scale actions wrongly.
 *
 * <p>Contracts pinned here:
 * 1) an empty ledger for a pool reports 0;
 * 2) a single event reports exactly that event's epoch seconds;
 * 3) several events report the NEWEST one (MAX, not first-write-wins);
 * 4) pools are isolated — an event on pool A never advances pool B's clock.
 *
 * <p>Real dev database via SpringBootTest (no mocks; fixtures use dedicated pool-key
 * markers deleted in the teardown).
 */
@SpringBootTest(classes = CoreApplication.class)
class ExecutorPoolScaleEventLedgerTest {

    private static final String POOL_A = "ledgerpoola";
    private static final String POOL_B = "ledgerpoolb";

    @Autowired
    private ExecutorPoolScaleEventLedger ledger;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM tp_cluster_scale_event WHERE pool_key LIKE 'ledgerpool%'");
    }

    private void insertEvent(String poolKey, int ageSeconds) {
        jdbcTemplate.update("""
                INSERT INTO tp_cluster_scale_event
                    (pool_key, action, desired_replicas, current_replicas, reason, created_at)
                VALUES (?, 'scale_up', 2, 1, 'fixture row for cooldown wiring',
                        now() - make_interval(secs => ?))
                """, poolKey, ageSeconds);
    }

    private long epochOfLatestRow(String poolKey) {
        return jdbcTemplate.queryForObject(
                "SELECT EXTRACT(epoch FROM MAX(created_at))::bigint FROM tp_cluster_scale_event WHERE pool_key = ?",
                Long.class, poolKey);
    }

    @Test
    void emptyLedger_reportsZero_neverRecentlyScaled() {
        assertEquals(0L, ledger.latestScaleEpochSeconds(POOL_A),
                "a pool with no scale history must read 0 so the cooldown guard never suppresses");
    }

    @Test
    void singleEvent_reportsThatEventsEpoch() {
        insertEvent(POOL_A, 300);
        assertEquals(epochOfLatestRow(POOL_A), ledger.latestScaleEpochSeconds(POOL_A));
    }

    @Test
    void severalEvents_reportTheNewest() {
        insertEvent(POOL_A, 900);
        insertEvent(POOL_A, 60);
        long now = System.currentTimeMillis() / 1000L;
        long reported = ledger.latestScaleEpochSeconds(POOL_A);
        assertTrue(Math.abs(now - 60 - reported) <= 2,
                "MAX(created_at) must win over the older event, reported=" + reported);
    }

    @Test
    void poolsAreIsolated() {
        insertEvent(POOL_A, 120);
        assertEquals(0L, ledger.latestScaleEpochSeconds(POOL_B),
                "an event on another pool must not advance this pool's cooldown clock");
    }

    @Test
    void recentEvents_emptyLedger_answersEmptyListNotNull() {
        List<ExecutorPoolScaleEvent> rows = ledger.recentEvents(POOL_A, 50);
        assertNotNull(rows);
        assertTrue(rows.isEmpty(), "a pool with no history reads an empty timeline window");
    }

    @Test
    void recentEvents_answersNewestFirst_withEveryColumnIntact() {
        insertEvent(POOL_A, 900);
        insertEvent(POOL_A, 300);
        insertEvent(POOL_A, 60);

        List<ExecutorPoolScaleEvent> rows = ledger.recentEvents(POOL_A, 50);

        assertEquals(3, rows.size());
        long now = System.currentTimeMillis() / 1000L;
        assertTrue(Math.abs((now - 60) - rows.get(0).createdEpochSeconds()) <= 2,
                "the newest event must head the window");
        assertTrue(rows.get(0).createdEpochSeconds() >= rows.get(1).createdEpochSeconds()
                        && rows.get(1).createdEpochSeconds() >= rows.get(2).createdEpochSeconds(),
                "the timeline window must be sorted newest-first");
        ExecutorPoolScaleEvent head = rows.get(0);
        assertEquals(POOL_A, head.poolKey());
        assertEquals("scale_up", head.action());
        assertEquals(2, head.desiredReplicas());
        assertEquals(1, head.currentReplicas());
        assertEquals("fixture row for cooldown wiring", head.reason());
    }

    @Test
    void recentEvents_poolFilter_returnsOnlyThatPool_andMissingFilterSpansTheFleet() {
        insertEvent(POOL_A, 300);
        insertEvent(POOL_B, 100);

        List<ExecutorPoolScaleEvent> filtered = ledger.recentEvents(POOL_A, 50);
        assertEquals(1, filtered.size());
        assertEquals(POOL_A, filtered.get(0).poolKey());

        List<ExecutorPoolScaleEvent> fleet = ledger.recentEvents(null, 50);
        assertTrue(fleet.size() >= 2,
                "no pool filter must span every pool's recent events, got=" + fleet.size());
    }

    @Test
    void recentEvents_limitTruncatesToTheNewestRows() {
        insertEvent(POOL_A, 900);
        insertEvent(POOL_A, 300);
        insertEvent(POOL_A, 60);

        List<ExecutorPoolScaleEvent> window = ledger.recentEvents(POOL_A, 2);

        assertEquals(2, window.size());
        long now = System.currentTimeMillis() / 1000L;
        assertTrue(Math.abs((now - 60) - window.get(0).createdEpochSeconds()) <= 2,
                "truncation must keep the newest rows, not the oldest");
    }
}
