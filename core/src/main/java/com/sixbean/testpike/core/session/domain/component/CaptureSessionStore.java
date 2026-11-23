package com.sixbean.testpike.core.session.domain.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Transient store for interactive capture sessions ( envelope shape —
 * takeover input queue: Redis-only, no new table, PG writes zero. The session
 * payload carries the operator's CDP debug endpoint string, which must never
 * reach a persistent surface (red line 4), so the store's whole lifecycle is
 * TTL + terminal-delete (R57: both guards, never one without the other).
 *
 * <p>Key names are single-sourced here (prefixes are public constants; callers
 * and fixtures must not re-spell them). The window is read fresh from
 * {@link SessionGovernanceKeys#captureTimeoutMinutes} on every write, so a
 * save on the configuration page takes effect without a restart.
 */
@Component
public class CaptureSessionStore {

    private static final Logger log = LoggerFactory.getLogger(CaptureSessionStore.class);

    /** Session payload key prefix (hash of one session, TTL-bound). */
    public static final String SESSION_KEY_PREFIX = "capture:session:";

    /** Candidate metadata list key prefix (executor write-back, same TTL family). */
    public static final String CANDIDATES_KEY_PREFIX = "capture:candidates:";

    /**
 * Hand-off queue : the list of session ids waiting for an
 * executor. Same shape as the takeover input queue — pop-on-claim, no
 * second claim. The key carries a TTL too: every enqueue refreshes it to
 * the capture window plus slack, so an idle queue can never outlive the
 * sessions inside it (R57 — a key nobody drains is still a leak).
 */
    public static final String PENDING_QUEUE_KEY = "capture:pending";

    private final StringRedisTemplate redis;
    private final SessionGovernanceKeys governanceKeys;
    private final ObjectMapper objectMapper;

    public CaptureSessionStore(StringRedisTemplate redis,
                               SessionGovernanceKeys governanceKeys,
                               ObjectMapper objectMapper) {
        this.redis = redis;
        this.governanceKeys = governanceKeys;
        this.objectMapper = objectMapper;
    }

    public static String sessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    public static String candidatesKey(String sessionId) {
        return CANDIDATES_KEY_PREFIX + sessionId;
    }

    /** Persist the payload with the hot-window TTL (refreshed on every write). */
    public void save(String sessionId, Map<String, Object> payload) {
        String key = sessionKey(sessionId);
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(payload),
                    Duration.ofMinutes(ttlFloorMinutes(governanceKeys.captureTimeoutMinutes())));
        } catch (Exception e) {
            log.warn("采集会话载荷序列化失败（sessionId={}）: {}", sessionId, e.getMessage());
            throw new IllegalStateException("capture session payload serialization failed", e);
        }
    }

    /**
     * Redis rejects SET with a zero/negative expire time, so a 0-minute kill
     * switch can ride the TTL as a 60-second key while the authoritative
     * liveness decision stays the window gate in the service (claim/find void
     * and delete on read — R57: a voided session never keeps a key forever).
     */
    private static long ttlFloorMinutes(int windowMinutes) {
        return Math.max(1L, windowMinutes);
    }

    /** Load one session payload; missing/expired = null (honest absence). */
    public Map<String, Object> load(String sessionId) {
        String raw = redis.opsForValue().get(sessionKey(sessionId));
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("采集会话载荷反序列化失败（sessionId={}）按不存在处理: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * Raw candidate rows (the metadata JSON written by {@link #appendCandidates}).
     * Absent/expired = empty list; parsing into the wizard shape happens in
     * the service so this store keeps no DTO dependency.
     */
    public List<String> candidateRows(String sessionId) {
        List<String> rows = redis.opsForList().range(candidatesKey(sessionId), 0, -1);
        return rows == null ? List.of() : rows;
    }

    /** Live candidate count (empty/absent = 0; observable growth, R57). */
    public long candidateCount(String sessionId) {
        Long n = redis.opsForList().size(candidatesKey(sessionId));
        return n == null ? 0L : n;
    }

    /** Remaining TTL in seconds for the session key (-2 absent / -1 no TTL). */
    public long ttlSeconds(String sessionId) {
        Long t = redis.getExpire(sessionKey(sessionId));
        return t == null ? -2L : t;
    }

    /** Terminal delete of both keys (idempotent; every exit path calls it). */
    public void remove(String sessionId) {
        redis.delete(sessionKey(sessionId));
        redis.delete(candidatesKey(sessionId));
    }

    /**
     * Queue a freshly opened session for hand-off. The queue TTL is the capture
     * window plus a minute of slack, refreshed on every enqueue: entries older
     * than that are unreachable anyway because their own session key is gone.
     */
    public void enqueue(String sessionId) {
        redis.opsForList().rightPush(PENDING_QUEUE_KEY, sessionId);
        redis.expire(PENDING_QUEUE_KEY, Duration.ofMinutes(governanceKeys.captureTimeoutMinutes() + 1L));
    }

    /** Pop the next session id awaiting a claim (null = queue empty). */
    public String claimPending() {
        return redis.opsForList().leftPop(PENDING_QUEUE_KEY);
    }

    /**
     * Append candidate rows (already serialized metadata JSON) and refresh the
     * list TTL to the capture window, so a long-running session cannot grow an
     * unbounded list (R57: growth always rides a TTL).
     */
    public long appendCandidates(String sessionId, List<String> serializedRows) {
        String key = candidatesKey(sessionId);
        Long total = redis.opsForList().rightPushAll(key, serializedRows);
        redis.expire(key, Duration.ofMinutes(ttlFloorMinutes(governanceKeys.captureTimeoutMinutes())));
        return total == null ? 0L : total;
    }

    /**
     * Drop one session from the hand-off queue (explicit abandon while it is
     * still waiting; a session that was already claimed has nothing to remove).
     */
    public long dequeue(String sessionId) {
        Long removed = redis.opsForList().remove(PENDING_QUEUE_KEY, 0, sessionId);
        return removed == null ? 0L : removed;
    }
}
