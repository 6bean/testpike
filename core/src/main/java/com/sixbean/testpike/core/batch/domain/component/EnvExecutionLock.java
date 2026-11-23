package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.common.lock.LockWatchdog;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Environment execution mutex: batches in the same environment execute mutually exclusively, and the Redis lock works across processes.
 * The lock key {@code env-exec:{envId}} is prefixed with lock: by RedisReentrantLock, and ownerId is set to the batch ID string.
 * The owner is the batch occupying the environment, and consecutive dispatch of multiple tasks in the same batch is naturally allowed by owner-hit renewal, following the same pattern as the review lock.
 * When ownerOf detection hits, it renews without increasing the reentrant count to prevent lock leaks caused by mismatched release and acquisition counts.
 * Lifecycle: the executor acquires the lock when claiming batch tasks, locking before claiming, and the watchdog renews at lease/3.
 * A process crash leaves no lease renewal and the lock is automatically released when the lease expires, bounding deadlock; batch terminal states such as cancel/finalize require explicit release.
 * Watchdog or planned coordinated cancellation explicitly releases the lock, and other batches skip claiming because the lock is occupied, which means queue waiting rather than parallel execution.
 * When the lock-holding batch reaches a terminal state, the lock is automatically released.
 * Stale convergence, an active cleanup beyond lease fallback, applies when the lock-holding batch is terminal (6/7/8) or has no non-terminal tasks (1/2).
 * Such stale locks are crash or rollback residue; releasing them allows new batches to proceed and prevents the live watchdog thread from incorrectly blocking queued batches.
 * Trial-run tasks with batch_id NULL do not acquire the environment lock because the domain is batch mutual exclusion and trial-run concurrency semantics remain unchanged.
 *
 */
@Component
public class EnvExecutionLock {

    /** Lease duration default in milliseconds; missing or invalid key fallback keeps behavior identical, the watchdog renews at lease/3, and this value bounds deadlock after process crash. */
    static final long DEFAULT_LEASE_MILLIS = 120_000L;

 /** The lease duration hot-update key requires Redis expiration times not to be hardcoded, and migration artifacts provide the seed. */
    public static final String LEASE_KEY = "lock.env-exec-lease-seconds";

    private final RedisReentrantLock redisLock;
    private final LockWatchdog watchdog;
    private final JdbcTemplate jdbcTemplate;
    private final com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder configCacheHolder;

    public EnvExecutionLock(RedisReentrantLock redisLock, LockWatchdog watchdog,
                            JdbcTemplate jdbcTemplate,
                            com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder configCacheHolder) {
        this.redisLock = redisLock;
        this.watchdog = watchdog;
        this.jdbcTemplate = jdbcTemplate;
        this.configCacheHolder = configCacheHolder;
    }

    /** Lease duration is read at the current time so each lock acquisition or renewal takes effect immediately with zero-restart hot updates; invalid or non-positive values fall back to the default, matching the ToolConfirmTokenStore baseline. */
    long leaseMillis() {
        String raw = configCacheHolder.get(LEASE_KEY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LEASE_MILLIS;
        }
        try {
            long seconds = Long.parseLong(raw.trim());
            return seconds > 0 ? seconds * 1000L : DEFAULT_LEASE_MILLIS;
        } catch (NumberFormatException e) {
            return DEFAULT_LEASE_MILLIS;
        }
    }

    /**
     * Attempt to acquire environment execution rights for a batch: if the batch already holds the lock, renew and allow; if it is idle, acquire the lock and start the watchdog.
     * If another batch holds the lock, retry once after stale convergence when the owner batch is terminal or has no non-terminal tasks.
     */
    public boolean tryAcquire(long envId, long batchId) {
        String key = lockKey(envId);
        String owner = String.valueOf(batchId);
        long lease = leaseMillis();
        String holder = redisLock.ownerOf(key);
        if (owner.equals(holder)) {
            // Owner hit means renewing the lease without increasing the reentrant count, as noted in the class comment about lock leaks; watchdog.start is idempotent.
            redisLock.renew(key, owner, lease);
            watchdog.start(key, owner, lease);
            return true;
        }
        if (holder != null) {
            if (!isHolderStale(holder)) {
                return false;
            }
            watchdog.stop(key, holder);
            redisLock.unlock(key, holder);
        }
        if (redisLock.tryLock(key, owner, lease)) {
            watchdog.start(key, owner, lease);
            return true;
        }
        // If another batch or a concurrent request from the same owner wins within the race window, the next claim retries and the owner-hit branch immediately allows it.
        return owner.equals(redisLock.ownerOf(key));
    }

    /** Release environment execution rights, called on terminal paths; a non-owner release is silent, side-effect-free, and idempotent. */
    public void release(long envId, long batchId) {
        String key = lockKey(envId);
        String owner = String.valueOf(batchId);
        watchdog.stop(key, owner);
        redisLock.unlock(key, owner);
    }

    /** Release by batch ID as the unified terminal-path entry: reverse-lookup the batch env_id, and missing environment or missing lock is silent and idempotent. */
    public void releaseByBatch(long batchId) {
        // After an environment is deleted and delete protection sets env_id to null, this reverse lookup returns a single NULL row, so stream.findFirst is forbidden.
 // A null element passed through Optional.of throws NPE, a regression; queryForList checks for emptiness and remains silent.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT env_id FROM tp_exec_batch WHERE id = ?", batchId);
        Object envId = rows.isEmpty() ? null : rows.get(0).get("env_id");
        if (envId instanceof Number num) {
            release(num.longValue(), batchId);
        }
    }

    /** The current environment's occupying batch ID, or null when there is no lock or parsing fails, is used for observation and assertions. */
    public Long currentHolder(long envId) {
        String owner = redisLock.ownerOf(lockKey(envId));
        try {
            return owner == null ? null : Long.valueOf(owner);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Determine whether the lock-holding batch is stale when its row does not exist due to test TRUNCATE or purge, when it is terminal (6/7/8), or when it has no non-terminal tasks (1/2). */
    private boolean isHolderStale(String holderOwnerId) {
        long holderBatchId;
        try {
            holderBatchId = Long.parseLong(holderOwnerId);
        } catch (NumberFormatException e) {
            return true; // A non-batch-ID owner, such as a historical dirty key, can be converged.
        }
        Integer aliveBatch = jdbcTemplate.query(
                "SELECT 1 FROM tp_exec_batch WHERE id = ? AND status NOT IN (6, 7, 8)",
                (rs, n) -> 1, holderBatchId).stream().findFirst().orElse(null);
        if (aliveBatch == null) {
            return true;
        }
        Long aliveTasks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE batch_id = ? AND status IN (1, 2)",
                Long.class, holderBatchId);
        return aliveTasks == null || aliveTasks == 0;
    }

    private static String lockKey(long envId) {
        return "env-exec:" + envId;
    }
}
