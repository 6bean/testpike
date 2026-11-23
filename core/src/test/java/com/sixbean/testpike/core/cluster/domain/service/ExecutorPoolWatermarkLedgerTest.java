package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolDemand;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolWatermarkPoint;
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
 * Pool-watermark history ledger contract (T294d water-level curve data plane).
 *
 * <p>The autoscaler page draws the water-level curve from persisted sample rows, so the
 * ledger owns three shapes pinned here:
 * 1) an empty history reads an empty list, never null;
 * 2) appendAll writes one row per demand snapshot with every column verbatim
 *    (depth / replicas / active / p95 must not drift through the insert);
 * 3) recentSeries returns newest-first rows, optionally filtered by executor type,
 *    with the requested window clamped into [1, MAX] inside the ledger (single source,
 *    the same posture as the scale-event ledger clamp);
 * 4) purgeExpired prunes only rows older than the retention bound (tp_sys_config key
 *    cluster.autoscale-watermark-retention-days, shipped default 30 days), so the
 *    append-only table can never grow without a recycling path (R57).
 *
 * <p>Real slot database via SpringBootTest (no mocks; fixtures use dedicated
 * executor-type markers deleted in the teardown, factory rows untouched).
 */
@SpringBootTest(classes = CoreApplication.class)
class ExecutorPoolWatermarkLedgerTest {

    private static final String TYPE_A = "watermarktypea";
    private static final String TYPE_B = "watermarktypeb";

    @Autowired
    private ExecutorPoolWatermarkLedger ledger;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixtures() {
        jdbcTemplate.update(
                "DELETE FROM tp_cluster_pool_watermark WHERE executor_type LIKE 'watermarktype%'");
    }

    private void insertBackdated(String executorType, int depth, double p95, int ageMinutes) {
        jdbcTemplate.update("""
                INSERT INTO tp_cluster_pool_watermark
                    (executor_type, pending_dispatch_depth, current_replicas,
                     active_executors, p95_wait_seconds, sampled_at)
                VALUES (?, ?, 2, 1, ?, now() - make_interval(mins => ?))
                """, executorType, depth, p95, ageMinutes);
    }

    @Test
    void emptySeries_readsEmptyListNotNull() {
        List<ExecutorPoolWatermarkPoint> got = ledger.recentSeries(TYPE_A, 10);
        assertNotNull(got);
        assertTrue(got.isEmpty(), "a never-sampled pool reads an empty curve window");
    }

    @Test
    void appendAll_writesOneRowPerDemand_withAllColumnsVerbatim() {
        ledger.appendAll(List.of(new ExecutorPoolDemand(TYPE_A, 7, 2, 1, 42.5, 0L)));

        List<ExecutorPoolWatermarkPoint> got = ledger.recentSeries(TYPE_A, 10);
        assertEquals(1, got.size());
        ExecutorPoolWatermarkPoint head = got.get(0);
        assertEquals(TYPE_A, head.executorType());
        assertEquals(7, head.pendingDispatchDepth());
        assertEquals(2, head.currentReplicas());
        assertEquals(1, head.activeExecutors());
        assertEquals(42.5, head.p95WaitSeconds(), 0.001);
        long nowEpoch = System.currentTimeMillis() / 1000L;
        assertTrue(Math.abs(nowEpoch - head.sampledEpochSeconds()) < 120,
                "appendAll stamps the sample moment server-side, not a caller guess");
    }

    @Test
    void recentSeries_ordersNewestFirst() {
        insertBackdated(TYPE_A, 1, 10.0, 30);
        insertBackdated(TYPE_A, 5, 50.0, 10);
        insertBackdated(TYPE_A, 9, 90.0, 2);

        List<ExecutorPoolWatermarkPoint> got = ledger.recentSeries(TYPE_A, 10);
        assertEquals(3, got.size());
        assertEquals(9, got.get(0).pendingDispatchDepth(), "the freshest sample leads the curve window");
        assertEquals(1, got.get(2).pendingDispatchDepth());
    }

    @Test
    void recentSeries_filtersByExecutorType_andNullSpansTheFleet() {
        insertBackdated(TYPE_A, 4, 40.0, 5);
        insertBackdated(TYPE_B, 8, 80.0, 5);

        List<ExecutorPoolWatermarkPoint> onlyA = ledger.recentSeries(TYPE_A, 10);
        assertEquals(1, onlyA.size());
        assertEquals(TYPE_A, onlyA.get(0).executorType());

        List<ExecutorPoolWatermarkPoint> fleet = ledger.recentSeries(null, 10);
        assertEquals(2, fleet.size(), "a missing filter honestly spans every pool");
    }

    @Test
    void windowRequest_isClampedLow_toASingleRowAtMinimum() {
        insertBackdated(TYPE_A, 3, 30.0, 6);
        insertBackdated(TYPE_A, 4, 40.0, 1);

        assertEquals(1, ledger.recentSeries(TYPE_A, 0).size(),
                "limit 0 normalizes to the smallest honest window, never zero rows");
        assertEquals(1, ledger.recentSeries(TYPE_A, -5).size());
    }

    @Test
    void purgeExpired_prunesOnlyRowsOlderThanRetention() {
        insertBackdated(TYPE_A, 2, 20.0, 31 * 24 * 60);
        insertBackdated(TYPE_A, 6, 60.0, 24 * 60);

        int purged = ledger.purgeExpired();
        assertTrue(purged >= 1, "the 31-day-old sample must fall outside the 30-day retention bound");

        List<ExecutorPoolWatermarkPoint> survived = ledger.recentSeries(TYPE_A, 10);
        assertEquals(1, survived.size());
        assertEquals(6, survived.get(0).pendingDispatchDepth(),
                "a sample inside the retention window is never pruned");
    }
}
