package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolDemand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T294c wiring: the autoscaler demand spectrum must be assembled from live fleet state
 * (online executors) and the real pending-dispatch queue, never from fabricated counts.
 *
 * <p>Contracts pinned here:
 * 1) pools come from the end-type tokens of ONLINE executors only (stale heartbeat = pool
 *    absent, same online semantics as {@code ExecutorService.listOnline()});
 * 2) the pending depth of a pool counts only tasks this pool could actually claim — the
 *    app-auto and perf capability-routing predicates are consumed as single sources, so a
 *    task whose engine dims no executor of the pool covers does not inflate demand;
 * 3) a task whose end-type tokens match two pools is counted in BOTH pool lines;
 * 4) p95WaitSeconds is the 95th percentile of the waited seconds of the claimable set;
 * 5) currentReplicas counts online instances of the pool, activeExecutors those with a
 *    running count above zero;
 * 6) an online pool with nothing claimable reports depth zero instead of disappearing;
 * 7) lastScaleEpochSeconds is sourced from the scale-event ledger per pool (0 = never
 *    scaled), so the cooldown guardrail no longer runs on a hard-wired zero.
 *
 * <p>Real dev database via SpringBootTest (no mocks; fixtures on a private high project-id
 * band and unique executor names, deleted in the teardown).
 */
@SpringBootTest(classes = CoreApplication.class)
class ExecutorPoolDemandServiceTest {

    private static final long MARKER_PROJECT = 93100001L;

    @Autowired
    private ExecutorPoolDemandService demandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", MARKER_PROJECT);
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 't294-demand-%'");
        jdbcTemplate.update("DELETE FROM tp_cluster_scale_event WHERE pool_key IN ('web', 'mobile')");
    }

    private long insertExecutor(String name, String endTypes, int runningCnt,
                                String capabilitiesJson, boolean heartbeatFresh) {
        jdbcTemplate.update("""
                INSERT INTO tp_executor (name, end_types, browsers, capacity, running_cnt,
                                         status, version, last_heartbeat, capabilities)
                VALUES (?, ?, 'chromium', 2, ?, 1, '1.0.0',
                        ?::timestamp, ?::jsonb)
                """, name, endTypes, runningCnt,
                heartbeatFresh ? java.time.LocalDateTime.now()
                               : java.time.LocalDateTime.now().minusHours(2),
                capabilitiesJson);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tp_executor WHERE name = ?", Long.class, name);
    }

    private void insertPendingTask(String actionJson) {
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, batch_id, action_json, status)
                VALUES (?, 1, NULL, ?, 1)
                """, MARKER_PROJECT, actionJson);
    }

    private void backdatePendingTaskSeconds(int seconds) {
        jdbcTemplate.update("""
                UPDATE tp_exec_task SET created_at = now() - make_interval(secs => ?)
                WHERE project_id = ?
                """, seconds, MARKER_PROJECT);
    }

    private Map<String, ExecutorPoolDemand> poolMap() {
        List<ExecutorPoolDemand> snapshot = demandService.snapshotPools();
        return snapshot.stream().collect(Collectors.toMap(
                ExecutorPoolDemand::executorType, Function.identity()));
    }

    private static String appEnvelope(String platform) {
        return "{\"kind\":\"app-auto-run\",\"engine\":{\"code\":\"appium\",\"platform\":\""
                + platform + "\",\"deviceKind\":\"phone\"}}";
    }

    @Test
    void appTaskBeyondPoolCapabilities_notCountedInDepth() {
        insertExecutor("t294-demand-web", "web", 0,
                "{\"engines\":[\"appium\"],\"platforms\":[\"android\"],\"deviceKinds\":[\"phone\"]}",
                true);
        insertPendingTask("{\"kind\":\"web\",\"steps\":[]}");
        insertPendingTask(appEnvelope("ios"));   // pool covers android only -> not claimable

        ExecutorPoolDemand web = poolMap().get("web");
        assertEquals(1, web.pendingDispatchDepth());
    }

    @Test
    void sharedEndTypeToken_taskCountedInBothPoolLines() {
        insertExecutor("t294-demand-a", "web", 0, "{\"platforms\":[\"android\"]}", true);
        insertExecutor("t294-demand-b", "web,mobile", 0, "{\"platforms\":[\"android\"]}", true);
        insertPendingTask("{\"kind\":\"web\",\"steps\":[]}");

        Map<String, ExecutorPoolDemand> pools = poolMap();
        assertEquals(1, pools.get("web").pendingDispatchDepth());
        assertEquals(1, pools.get("mobile").pendingDispatchDepth());
        // the web pool is backed by BOTH executors (the second declares "web,mobile")
        assertEquals(2, pools.get("web").currentReplicas());
        assertEquals(1, pools.get("mobile").currentReplicas());
    }

    @Test
    void p95Wait_secondsOfClaimableSet() {
        insertExecutor("t294-demand-web", "web", 0, "{}", true);
        insertPendingTask("{\"kind\":\"web\",\"steps\":[]}");
        backdatePendingTaskSeconds(100);

        ExecutorPoolDemand web = poolMap().get("web");
        assertTrue(web.p95WaitSeconds() >= 90 && web.p95WaitSeconds() <= 130,
                "single 100s-old claimable task must yield a p95 near 100, got " + web.p95WaitSeconds());
    }

    private void insertScaleEvent(String poolKey, int ageSeconds) {
        jdbcTemplate.update("""
                INSERT INTO tp_cluster_scale_event
                    (pool_key, action, desired_replicas, current_replicas, reason, created_at)
                VALUES (?, 'scale_up', 2, 1, 'fixture row for cooldown wiring',
                        now() - make_interval(secs => ?))
                """, poolKey, ageSeconds);
    }

    @Test
    void lastScaleEpochSeconds_consumesScaleEventLedgerPerPool() {
        insertExecutor("t294-demand-web", "web", 0, "{}", true);
        insertExecutor("t294-demand-mob", "mobile", 0, "{}", true);
        insertScaleEvent("web", 120);

        Map<String, ExecutorPoolDemand> pools = poolMap();
        long expectedEpoch = System.currentTimeMillis() / 1000L - 120;
        assertTrue(Math.abs(pools.get("web").lastScaleEpochSeconds() - expectedEpoch) <= 2,
                "the web pool line must carry the newest ledger event epoch, got "
                        + pools.get("web").lastScaleEpochSeconds());
        assertEquals(0L, pools.get("mobile").lastScaleEpochSeconds(),
                "a pool without scale events must still report never-recently-scaled");
    }

    @Test
    void staleHeartbeatExecutor_poolAbsent() {
        insertExecutor("t294-demand-web", "web", 0, "{}", false);   // heartbeat 2h old

        assertFalse(poolMap().containsKey("web"),
                "an executor with a stale heartbeat must not create a pool line");
    }

    @Test
    void activeExecutors_countsRunningPositiveInstances() {
        insertExecutor("t294-demand-w1", "web", 0, "{}", true);
        insertExecutor("t294-demand-w2", "web", 2, "{}", true);

        ExecutorPoolDemand web = poolMap().get("web");
        assertEquals(2, web.currentReplicas());
        assertEquals(1, web.activeExecutors());
    }

    @Test
    void onlinePoolWithoutPendingTasks_depthZero() {
        insertExecutor("t294-demand-web", "web", 0, "{}", true);

        ExecutorPoolDemand web = poolMap().get("web");
        assertEquals(0, web.pendingDispatchDepth());
        assertEquals(0.0, web.p95WaitSeconds());
        assertEquals(0, web.lastScaleEpochSeconds());
    }
}
