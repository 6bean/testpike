package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.project.domain.component.EnvParamCipherSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch context storage supports cross-machine transfer of session artifacts and shares the same storage baseline as batch parameter snapshots without adding a new layer.
 * <p>The authoritative store is the database table {@code tp_batch_context}; strict acceptance for resume replay forbids depending on Redis as a pure cache solution.
 * If a crash exceeds the TTL the chain breaks, matching the BatchParamSnapshotStore ruling, and the Redis key is {@code batch-ctx:{batchId}:{key}}.
 * Redis is only an acceleration layer, backfilling after put persists to the database successfully during afterCommit, and a read miss queries the database and backfills.
 * Any Redis exception falls back to direct database reads without failing claim.
 * <p>The value is an ENC envelope ({@code ENC:} plus ConfigCipher AES-GCM), and the prefix literal is reused.
 * The {@link EnvParamCipherSupport#ENC_PREFIX} single point is reused because artifacts contain session credentials.
 * Plaintext must not be persisted to disk or cached as a hard red line; this class exposes only plaintext input and plaintext output externally, and logs must not print values.
 * <p>The TTL uses the Kubernetes hot-update key {@code batch.context-ttl-seconds}, with a migration seed default of 1800 seconds and fresh reads each time.
 * Invalid or non-positive values fall back to the default because Redis expiration time for this purpose must not be hardcoded under the agreed convention.
 */
@Component
public class BatchContextStore {

    private static final Logger log = LoggerFactory.getLogger(BatchContextStore.class);

    /** The Redis read-through key prefix is batch-ctx:{batchId}:{key}, following the same key-space discipline as the lock component. */
    static final String REDIS_KEY_PREFIX = "batch-ctx:";

 /** The TTL hot-update key belongs to the Kubernetes configuration plane and uses the migration seed. */
    public static final String TTL_KEY = "batch.context-ttl-seconds";

    /** The default TTL seconds are used when the key is missing or invalid, and one cross-machine session transfer is far shorter than this window. */
    static final long DEFAULT_TTL_SECONDS = 1800;

    /** The session artifact key prefix artifact:xxx is the key space claimed by the claim envelope distribution plane, while other keys are used only inside the batch. */
    public static final String ARTIFACT_PREFIX = "artifact:";

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redis;
    private final ConfigCacheHolder configCacheHolder;
    private final AfterCommitExecutor afterCommitExecutor;
    private final ConfigCipher configCipher;

    public BatchContextStore(JdbcTemplate jdbcTemplate, StringRedisTemplate redis,
                             ConfigCacheHolder configCacheHolder,
                             AfterCommitExecutor afterCommitExecutor,
                             ConfigCipher configCipher) {
        this.jdbcTemplate = jdbcTemplate;
        this.redis = redis;
        this.configCacheHolder = configCacheHolder;
        this.afterCommitExecutor = afterCommitExecutor;
        this.configCipher = configCipher;
    }

    /**
     * Writing a context key accepts plaintext input, internally persists an ENC envelope to the database, performs INSERT or UPDATE in the same transaction, and leaves no partial state on rollback.
     * Backfill Redis during afterCommit, because writing Redis inside the transaction is forbidden since claimants may read uncommitted values before commit.
     */
    public void put(long batchId, String ctxKey, String plainValue) {
        String envelope = toEnvelope(plainValue);
        jdbcTemplate.update("""
                INSERT INTO tp_batch_context (batch_id, ctx_key, value_cipher, updated_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (batch_id, ctx_key) DO UPDATE
                    SET value_cipher = EXCLUDED.value_cipher, updated_at = now()
                """, batchId, ctxKey, envelope);
        afterCommitExecutor.execute(() -> fillCacheQuietly(batchId, ctxKey, envelope));
    }

    /** Read-through returns an ENC envelope for internal and test assertions of ciphertext form, uses Redis on a hit, reads the database and backfills on a miss, and returns null when no row exists. */
    public String readEnvelope(long batchId, String ctxKey) {
        String key = redisKey(batchId, ctxKey);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return cached;
            }
        } catch (RuntimeException e) {
            log.warn("批次上下文缓存读取失败，降级直读库 batchId={}: {}", batchId, e.getMessage());
        }
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT value_cipher FROM tp_batch_context WHERE batch_id = ? AND ctx_key = ?",
                String.class, batchId, ctxKey);
        if (rows.isEmpty()) {
            return null;
        }
        String stored = rows.get(0);
        fillCacheQuietly(batchId, ctxKey, stored);
        return stored;
    }

    /**
     * Claim assembly maps all {@code artifact:} keys in the batch to a {prefix-stripped key: decrypted plaintext} map, and the claim envelope tail appends the sessionArtifacts channel.
     * Protocol 2.3 defines the sessionArtifacts channel, and the returned map contains plaintext session credentials that must not be printed throughout the call chain.
 * No artifacts return an empty map, preserving zero drift for legacy data and following the default convention of the envVars tail-append precedent.
     */
    public Map<String, String> readArtifacts(long batchId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT ctx_key, value_cipher FROM tp_batch_context"
                        + " WHERE batch_id = ? AND ctx_key LIKE ? ORDER BY ctx_key",
                batchId, ARTIFACT_PREFIX + "%");
        Map<String, String> artifacts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String ctxKey = String.valueOf(row.get("ctx_key"));
            artifacts.put(ctxKey.substring(ARTIFACT_PREFIX.length()),
                    fromEnvelope(String.valueOf(row.get("value_cipher"))));
        }
        return artifacts;
    }

    /** Plaintext is converted into an ENC envelope, rejects with a human-readable error when the key is not configured, and forbids plaintext fallback under the same fail-closed ruling as EnvParamCipherSupport. */
    private String toEnvelope(String plainValue) {
        if (!configCipher.isEnabled()) {
            throw new ServiceException("系统未配置 CONFIG_AES_KEY，无法加密保存批次会话上下文",
                    BizErrorCode.INTERNAL_ERROR);
        }
        return EnvParamCipherSupport.ENC_PREFIX + configCipher.encrypt(plainValue);
    }

    /** An ENC envelope is converted into plaintext, throws a human-readable error for malformed ciphertext instead of failing silently, and the claim assembly side catches it and falls back as no artifacts. */
    private String fromEnvelope(String stored) {
        if (!stored.startsWith(EnvParamCipherSupport.ENC_PREFIX)) {
            throw new ServiceException("批次上下文密文形态异常", BizErrorCode.INTERNAL_ERROR);
        }
        return configCipher.decrypt(stored.substring(EnvParamCipherSupport.ENC_PREFIX.length()));
    }

    /** Cache backfill failure only emits a warning because the authoritative database side has already persisted and the next read-through naturally fills the missing cache. */
    private void fillCacheQuietly(long batchId, String ctxKey, String envelope) {
        try {
            redis.opsForValue().set(redisKey(batchId, ctxKey), envelope,
                    Duration.ofSeconds(ttlSeconds()));
        } catch (RuntimeException e) {
            log.warn("批次上下文缓存回填失败（不影响权威落库）batchId={}: {}", batchId, e.getMessage());
        }
    }

    private static String redisKey(long batchId, String ctxKey) {
        return REDIS_KEY_PREFIX + batchId + ":" + ctxKey;
    }

    /** The TTL is read fresh, invalid or non-positive values fall back to the default, hot updates take effect immediately, caching is forbidden, and the baseline matches BatchParamSnapshotStore.ttlHours. */
    long ttlSeconds() {
        String raw = configCacheHolder.get(TTL_KEY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TTL_SECONDS;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_TTL_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_TTL_SECONDS;
        }
    }
}
