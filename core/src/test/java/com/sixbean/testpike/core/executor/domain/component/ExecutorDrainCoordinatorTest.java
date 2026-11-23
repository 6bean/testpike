package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.lease.domain.service.ResourceLeaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executor pool drain protocol decision-side tests (AA wave T294a package 1).
 * <p>Covers the S3 semantic chain stop-claim → running-zero → exit with a drain
 * ownership lease (kind=executor) and the N4 timeout reclaim fallback:
 * begin/finish flag+lease pairing, duplicate begin tolerance, drain state read
 * against the real running task count, expired-lease sweep with live command
 * self-heal versus abandoned drain reclaim back to pool, and the per-kind TTL
 * resolution stage. Real dev database, no mocks (strict testing iron law).
 */
@SpringBootTest(classes = CoreApplication.class)
class ExecutorDrainCoordinatorTest {

    @Autowired
    private ExecutorDrainCoordinator coordinator;

    @Autowired
    private ResourceLeaseService leaseService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long createdExecutorId;
    private final List<Long> createdCommandIds = new ArrayList<>();
    private final List<Long> createdTaskIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long taskId : createdTaskIds) {
            jdbc.update("DELETE FROM tp_exec_task WHERE id = ?", taskId);
        }
        for (Long cmdId : createdCommandIds) {
            jdbc.update("DELETE FROM tp_cluster_command WHERE id = ?", cmdId);
        }
        if (createdExecutorId != null) {
            jdbc.update("DELETE FROM tp_resource_lease WHERE kind = 'executor' AND resource_ref = ?",
                    String.valueOf(createdExecutorId));
            jdbc.update("DELETE FROM tp_executor WHERE id = ?", createdExecutorId);
        }
    }

    /** Register a private fixture executor row directly (decision-side fixture). */
    private long newExecutor(String namePrefix) {
        Long id = jdbc.queryForObject(
                "INSERT INTO tp_executor (name, capacity, status, last_heartbeat)"
                        + " VALUES (?, 2, 1, now()) RETURNING id",
                Long.class, namePrefix + "-" + System.nanoTime());
        assertNotNull(id);
        createdExecutorId = id;
        return id;
    }

    private boolean drainFlag(long executorId) {
        Boolean flag = jdbc.queryForObject(
                "SELECT drain_requested FROM tp_executor WHERE id = ?", Boolean.class, executorId);
        return Boolean.TRUE.equals(flag);
    }

    /** In-flight task row (status 2=running per tp_exec_task column contract). */
    private long insertRunningTask(long executorId) {
        Long id = jdbc.queryForObject(
                "INSERT INTO tp_exec_task (project_id, case_id, executor_id, status)"
                        + " VALUES (-1, -1, ?, 2) RETURNING id", Long.class, executorId);
        createdTaskIds.add(id);
        return id;
    }

    /** Pending, non-expired scale-in command row pointing at the executor. */
    private long insertLiveScaleInCommand(long executorId) {
        Long id = jdbc.queryForObject(
                "INSERT INTO tp_cluster_command (command_type, target_type, target_id, payload,"
                        + " status, expires_at)"
                        + " VALUES ('scale-in', 'executor', ?, '{}'::jsonb, 0, now() + interval '1 hour')"
                        + " RETURNING id", Long.class, executorId);
        createdCommandIds.add(id);
        return id;
    }

    @Test
    void beginDrain_setsFlagAndHoldsExecutorLease() {
        long id = newExecutor("drain-begin");
        coordinator.beginDrain(id, "cmd:1001");

        assertTrue(drainFlag(id), "beginDrain must raise drain_requested (stop claim)");
        assertEquals("cmd:1001", leaseService.activeHolder("executor", String.valueOf(id)),
                "drain must hold a kind=executor ownership lease");
    }

    @Test
    void beginDrain_unknownExecutor_rejectsInPlainWords() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> coordinator.beginDrain(990099001L, "cmd:1"));
        assertTrue(e.getMessage().contains("执行器"), "plain-word refusal expected: " + e.getMessage());
    }

    @Test
    void beginDrain_duplicateIsTolerant_firstTokenOwnerKept() {
        long id = newExecutor("drain-dup");
        coordinator.beginDrain(id, "cmd:1001");
        // Duplicate scale-in while draining: no exception, flag stays, first token survives
        coordinator.beginDrain(id, "cmd:1002");
        assertTrue(drainFlag(id));
        assertEquals("cmd:1001", leaseService.activeHolder("executor", String.valueOf(id)),
                "duplicate begin must not steal the live drain token");
    }

    @Test
    void drainState_reportsRunningZeroOnlyWhenNoTaskInFlight() {
        long id = newExecutor("drain-state");
        coordinator.beginDrain(id, "cmd:1001");

        long taskId = insertRunningTask(id);
        ExecutorDrainCoordinator.DrainSnapshot busy = coordinator.drainState(id);
        assertTrue(busy.drainRequested());
        assertEquals(1, busy.runningTasks(), "running task must be counted from tp_exec_task truth");
        assertFalse(busy.drained(), "not drained while a task is in flight");

        jdbc.update("UPDATE tp_exec_task SET status = 3 WHERE id = ?", taskId);
        ExecutorDrainCoordinator.DrainSnapshot idle = coordinator.drainState(id);
        assertEquals(0, idle.runningTasks());
        assertTrue(idle.drained(), "drained = flag raised and running count zero");
    }

    @Test
    void finishDrain_clearsFlagReleasesLease_andIsIdempotent() {
        long id = newExecutor("drain-finish");
        coordinator.beginDrain(id, "cmd:1001");
        coordinator.finishDrain(id, "cmd:1001");

        assertFalse(drainFlag(id), "successful exit receipt must release the drain flag");
        assertNull(leaseService.activeHolder("executor", String.valueOf(id)));
        coordinator.finishDrain(id, "cmd:1001"); // second call: no side effects, no error
    }

    @Test
    void sweepStaleDrains_abandonedDrainWithoutLiveCommand_returnsNodeToPool() {
        long id = newExecutor("drain-sweep-abandon");
        coordinator.beginDrain(id, "cmd:1001");
        expireDrainLease(id);

        int swept = coordinator.sweepStaleDrains();

        assertTrue(swept >= 1, "sweep must report the reclaimed executor lease");
        assertFalse(drainFlag(id),
                "expired lease with no unfinished scale-in command = abandoned drain, node back to pool");
        assertNull(leaseService.activeHolder("executor", String.valueOf(id)));
    }

    @Test
    void sweepStaleDrains_liveCommandPending_selfHealsTokenKeepsDrain() {
        long id = newExecutor("drain-sweep-live");
        coordinator.beginDrain(id, "cmd:1001");
        long cmdId = insertLiveScaleInCommand(id);
        expireDrainLease(id);

        coordinator.sweepStaleDrains();

        assertTrue(drainFlag(id), "live scale-in command pending: drain intent survives the lease timeout");
        assertEquals("cmd:" + cmdId, leaseService.activeHolder("executor", String.valueOf(id)),
                "sweep must re-anchor the token on the in-flight command (N4 self-heal)");
    }

    @Test
    void drainTokenLifetime_resolvesFromTtlTiering_neverZero() {
        // TTL tiering in ResourceLeaseServiceImpl: the executor stage reads the drain
        // lease key first and falls back to the global key (hot-updatable, no hardcode),
        // so the issued token must carry a positive, bounded lifetime.
        long id = newExecutor("drain-ttl");
        coordinator.beginDrain(id, "cmd:1001");
        Long seconds = jdbc.queryForObject(
                "SELECT extract(epoch FROM (expires_at - acquired_at))::bigint"
                        + " FROM tp_resource_lease WHERE kind = 'executor' AND resource_ref = ?"
                        + " AND state = 'active'", Long.class, String.valueOf(id));
        assertNotNull(seconds);
        assertTrue(seconds > 0 && seconds <= 86400,
                "drain lease lifetime must be positive and bounded, got " + seconds);
    }

    private void expireDrainLease(long executorId) {
        jdbc.update("UPDATE tp_resource_lease SET expires_at = now() - interval '1 second'"
                        + " WHERE kind = 'executor' AND resource_ref = ? AND state = 'active'",
                String.valueOf(executorId));
    }

    /** Cross-check: sweep(kind=executor) must never touch foreign-kind rows. */
    @Test
    void sweepStaleDrains_otherKindLeasesUntouched() {
        long id = newExecutor("drain-sweep-isolate");
        String foreignRef = "drain-isolate-" + System.nanoTime();
        leaseService.acquire("data", foreignRef, 1L, null, null, "9001");
        jdbc.update("UPDATE tp_resource_lease SET expires_at = now() - interval '1 second'"
                        + " WHERE kind = 'data' AND resource_ref = ?", foreignRef);
        coordinator.beginDrain(id, "cmd:1001");
        expireDrainLease(id);

        coordinator.sweepStaleDrains();

        List<String> stillActive = jdbc.queryForList(
                "SELECT resource_ref FROM tp_resource_lease"
                        + " WHERE kind = 'data' AND resource_ref = ? AND state = 'active'",
                String.class, foreignRef);
        assertEquals(1, stillActive.size(), "sweep(kind=executor) must not reclaim foreign-kind rows");
        jdbc.update("DELETE FROM tp_resource_lease WHERE kind = 'data' AND resource_ref = ?", foreignRef);
    }
}
