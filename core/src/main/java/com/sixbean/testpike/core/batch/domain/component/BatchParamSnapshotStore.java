package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Batch parameter snapshots are stored by persisting to the primary database and using a Redis read-through cache.
 * The authoritative source is the DB table {@code tp_batch_param_snapshot}; hard acceptance for continuation replay must not depend on Redis, a pure cache solution.
 * A crash beyond TTL breaks the chain; the Redis key {@code batch-snap:{batchId}} is only an acceleration layer after successful persist to database at startup.
 * afterCommit backfill; a claim read miss falls back to the DB and backfills; any Redis exception falls back to direct DB read and does not break the claim.
 * The TTL is the K8 hot-update key {@code batch.snapshot-ttl-hours}; the migration seed default is 24h, and it is read fresh each time.
 * Invalid or non-positive values fall back to the default because Redis expiration time for this purpose must not be hardcoded under the agreed convention.
 * Warning: the envelope contains credential ciphertext—values must not be logged; this class only passes through strings.
 */
@Component
public class BatchParamSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(BatchParamSnapshotStore.class);

    /** The Redis read-through key prefix is observable for operations and follows the same key-space discipline as the lock component. */
    static final String REDIS_KEY_PREFIX = "batch-snap:";

 /** The TTL hot-update key belongs to the Kubernetes configuration plane and uses the migration seed. */
    public static final String TTL_KEY = "batch.snapshot-ttl-hours";

    /** The TTL default is in hours; fallback occurs when the key is missing or invalid, and normal batch execution is much shorter than this window. */
    static final int DEFAULT_TTL_HOURS = 24;

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redis;
    private final ConfigCacheHolder configCacheHolder;
    private final AfterCommitExecutor afterCommitExecutor;

    public BatchParamSnapshotStore(JdbcTemplate jdbcTemplate, StringRedisTemplate redis,
                                   ConfigCacheHolder configCacheHolder,
                                   AfterCommitExecutor afterCommitExecutor) {
        this.jdbcTemplate = jdbcTemplate;
        this.redis = redis;
        this.configCacheHolder = configCacheHolder;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    /**
     * Persist the pinned startup version to database (INSERT in the same transaction; rollback leaves no partial snapshot) and backfill Redis afterCommit.
     * Writing Redis within the transaction is prohibited, because a claim before commit may read a snapshot of an uncommitted batch.
     */
    public void save(long batchId, int gen, String envelope) {
        jdbcTemplate.update("""
                INSERT INTO tp_batch_param_snapshot (batch_id, gen, params_json, created_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (batch_id) DO UPDATE
                    SET gen = EXCLUDED.gen, params_json = EXCLUDED.params_json,
                        created_at = now()
                """, batchId, gen, envelope);
        afterCommitExecutor.execute(() -> fillCacheQuietly(batchId, envelope));
    }

    /** Read-through: on a Redis hit use the value; on a miss read from DB and backfill; if there is no DB row return null, and the caller takes the existing fallback branch. */
    public String readEnvelope(long batchId) {
        String key = REDIS_KEY_PREFIX + batchId;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return cached;
            }
        } catch (RuntimeException e) {
            log.warn("批次参数快照缓存读取失败，降级直读库 batchId={}: {}", batchId, e.getMessage());
        }
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT params_json FROM tp_batch_param_snapshot WHERE batch_id = ?",
                String.class, batchId);
        if (rows.isEmpty()) {
            return null;
        }
        String stored = rows.get(0);
        fillCacheQuietly(batchId, stored);
        return stored;
    }

    /** The current batch snapshot generation is 0 when there is no row, the caller decides fallback based on it, and rerun does not rebuild, so the same batch always returns the startup value. */
    public int currentGen(long batchId) {
        List<Integer> gens = jdbcTemplate.queryForList(
                "SELECT gen FROM tp_batch_param_snapshot WHERE batch_id = ?", Integer.class, batchId);
        return gens.isEmpty() ? 0 : gens.get(0);
    }

    /** Cache backfill failure only emits a warning because the authoritative database side has already persisted and the next read-through naturally fills the missing cache. */
    private void fillCacheQuietly(long batchId, String envelope) {
        try {
            redis.opsForValue().set(REDIS_KEY_PREFIX + batchId, envelope,
                    Duration.ofHours(ttlHours()));
        } catch (RuntimeException e) {
            log.warn("批次参数快照缓存回填失败（不影响权威落库）batchId={}: {}", batchId, e.getMessage());
        }
    }

    /** The TTL is read fresh; invalid or non-positive values fall back to the default, hot updates take effect immediately, caching is prohibited, and the approach follows the same baseline as ToolConfirmTokenStore. */
    long ttlHours() {
        String raw = configCacheHolder.get(TTL_KEY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TTL_HOURS;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_TTL_HOURS;
        } catch (NumberFormatException e) {
            return DEFAULT_TTL_HOURS;
        }
    }
}
