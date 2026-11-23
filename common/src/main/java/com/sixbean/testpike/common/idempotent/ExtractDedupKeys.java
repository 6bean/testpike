package com.sixbean.testpike.common.idempotent;

import com.sixbean.testpike.common.lock.RedisReentrantLock;

/**
 * Single source for the F2 document-extraction consume keys .
 * <p>Before this file the key shape {@code ai-doc-extract:{docId}:{token}} was an
 * inline literal in the ai-service consumer while the core preemption probe needs
 * the same shape to scan live-heartbeat keys — a second consumer of the layout,
 * so the composition lives here once (R40: no second copy of the key grammar).
 * <p>Storage layout (mirrors {@link MqIdempotentConsumeSupport} +
 * {@link RedisReentrantLock}): the consume lock is stored at
 * {@code lock:mq-consume:ai-doc-extract:{docId}:{token}} and its liveness
 * heartbeat appends the {@code :hb} suffix.
 */
public final class ExtractDedupKeys {

    /** Dedup-domain segment shared by producer, consumer and the core probe. */
    public static final String DEDUP_DOMAIN = "ai-doc-extract";

    /**
     * Storage-key prefix shared by every generation of the same document
     * ({@code ai-doc-extract:{docId}}). Also the audit-row prefix the consumer
     * writes on duplicate_drop / entity_gone_skip — same single source.
     */
    public static String lockKeyPrefix(long documentId) {
        return DEDUP_DOMAIN + ":" + documentId;
    }

    /** Idempotent dedup key of one extraction generation ({@code domain:docId:token}). */
    public static String dedupKey(long documentId, String extractToken) {
        return lockKeyPrefix(documentId) + ":" + extractToken;
    }

    /** Raw Redis storage key of the liveness heartbeat for one generation. */
    public static String consumeHeartbeatKey(long documentId, String extractToken) {
        return RedisReentrantLock.LOCK_KEY_PREFIX
                + MqIdempotentConsumeSupport.CONSUME_LOCK_PREFIX
                + dedupKey(documentId, extractToken)
                + RedisReentrantLock.HEARTBEAT_SUFFIX;
    }

    /** Scan pattern matching a live heartbeat of ANY generation of this document. */
    public static String consumeHeartbeatPattern(long documentId) {
        return RedisReentrantLock.LOCK_KEY_PREFIX
                + MqIdempotentConsumeSupport.CONSUME_LOCK_PREFIX
                + lockKeyPrefix(documentId) + ":*"
                + RedisReentrantLock.HEARTBEAT_SUFFIX;
    }

    private ExtractDedupKeys() {
    }
}
