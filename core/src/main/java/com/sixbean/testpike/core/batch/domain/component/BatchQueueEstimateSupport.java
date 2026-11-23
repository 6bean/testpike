package com.sixbean.testpike.core.batch.domain.component;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Batch queue estimation shared component: the queue-position acquisition pattern and the multi-end matrix fan-out cost preflight check share the same source, and duplicate implementations are prohibited.
 * <p>Includes the queue-position advisory lock key, pending dispatch count SQL, idle capacity SQL, and per-task estimation coefficient.
 * The formula queue count ÷ idle capacity × coefficient is centralized here and shared by the tail segment of batch start and matrix fan-out preflight/dispatch.
 * Adjusting the coefficient takes effect in one place; the lock is automatically released at transaction commit (the caller must be inside a transaction).
 */
@Component
public class BatchQueueEstimateSupport {

    /** Queue heuristic: expected duration per task in minutes; estimatedStartAt = queue count ÷ idle capacity × this value. */
    public static final int EST_MINUTES_PER_TASK = 5;
    /** Executor online status (tp_executor.status=1, same source as ExecutorServiceImpl). */
    public static final int EXECUTOR_ONLINE = 1;
    /**
 * The queue-position advisory lock key uses the database-wide unique namespace of pg_advisory_xact_lock.
     * Using a fixed business key serializes the cross-transaction critical section from count to place; the lock is automatically released at transaction commit with no leak.
     */
    public static final long QUEUE_POS_LOCK_KEY = 20260904_135L;

    private final JdbcTemplate jdbcTemplate;

    public BatchQueueEstimateSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

 /** Lock the position-acquisition critical section (must be in the caller's transaction; serializes count→place and fixes an empirically observed cross-batch race condition). */
    public void lockQueuePosition() {
        jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(?)::text", String.class,
                QUEUE_POS_LOCK_KEY);
    }

    /** Pending dispatch task count (excludeBatchId excludes the current batch to prevent self-counting; null uses the full scope including trial-run tasks). */
    public long pendingAheadCount(Long excludeBatchId) {
        if (excludeBatchId == null) {
            Long c = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_exec_task WHERE status = 1", Long.class);
            return c == null ? 0 : c;
        }
        Long c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE status = 1"
                        + " AND batch_id IS DISTINCT FROM ?", Long.class, excludeBatchId);
        return c == null ? 0 : c;
    }

    /** Total idle capacity of online executors (no online executor returns 0; the caller uses a fallback minimum of 1). */
    public long idleCapacity() {
        Long idle = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(GREATEST(capacity - running_cnt, 0)), 0) FROM tp_executor"
                        + " WHERE status = ?", Long.class, EXECUTOR_ONLINE);
        return idle == null ? 0 : idle;
    }

    /** Queue estimation in minutes = ceil(queue count ÷ capacity) × coefficient; this formula is centralized here. */
    public long estimateMinutes(long ahead, long capacity) {
        return (long) Math.ceil((double) ahead / Math.max(capacity, 1)) * EST_MINUTES_PER_TASK;
    }

    /** Per-task estimated minutes (the M in the preflight check 'N executors × M minutes' is this coefficient centralized here; callers must not hard-code it). */
    public int minutesPerTask() {
        return EST_MINUTES_PER_TASK;
    }
}
