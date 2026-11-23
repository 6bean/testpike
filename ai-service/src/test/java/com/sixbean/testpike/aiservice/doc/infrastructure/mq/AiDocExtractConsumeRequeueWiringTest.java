package com.sixbean.testpike.aiservice.doc.infrastructure.mq;

import com.sixbean.testpike.aiservice.testsupport.HostRedisSession;
import com.sixbean.testpike.aiservice.doc.domain.service.DocExtractService;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.idempotent.IdempotentAspect;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Requeue wiring for crashed lock holders .
 * <p>A redelivery that finds the consume lock held while its liveness
 * heartbeat is dead means the previous holder crashed: acking it as a
 * duplicate (with a duplicate_drop audit) would hang the extraction task
 * until the 15-minute lease expires with nobody working it. This class pins
 * the two halves of the recovery path:
 * <ul>
 * <li>consumer branch — HEARTBEAT_DEAD_REQUEUE must surface as Spring
 * AMQP's ImmediateRequeueAmqpException so the container nacks with
 * requeue=true, and must never write the duplicate_drop audit;</li>
 * <li>live-holder zero drift — lock warm with a heartbeat stays a plain
 * DUPLICATE (ack + audit), the pre- behavior byte-identical.</li>
 * </ul>
 * <p>L2 with the real host Redis 6379 (same posture as
 * AiDocExtractConsumeIdempotencyTest): lock/heartbeat/idempotent keys are
 * planted and cleaned per run id, never mocked. The CoreGateway mock isolates
 * the audit HTTP boundary only.
 */
class AiDocExtractConsumeRequeueWiringTest {

    private static HostRedisSession session;
    private static StringRedisTemplate redis;
    private static final long DOC_BASE = 910_000_000L + (System.nanoTime() % 50_000_000L);

    @BeforeAll
    static void connect() { session = HostRedisSession.connect(); redis = session.template(); }

    @AfterAll
    static void disconnect() { if (session != null) session.close(); }

    private AiDocExtractConsumer harness(ExtractCallCounter service,
                                         RedisReentrantLock lock,
                                         CoreGateway gateway) {
        // segment 3 wiring: the consume guard and the runner proxy must share ONE
        // aspect instance — the orphan-marker takeover reaches the E: stamp
        // through exactly the aspect that wrote it.
        IdempotentAspect aspect = new IdempotentAspect(redis);
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(lock, aspect);
        AspectJProxyFactory pf = new AspectJProxyFactory(new AiDocExtractIdempotentRunner(service));
        pf.addAspect(aspect);
        pf.setProxyTargetClass(true);
        return new AiDocExtractConsumer(pf.getProxy(), support, gateway);
    }

    private static String dedupKey(long documentId, String extractToken) {
        return "ai-doc-extract:" + documentId + ":" + extractToken;
    }

    /** Remove every key the scenario plants (lock, heartbeat, idempotent marker). */
    private static void cleanup(String dedup) {
        redis.delete(RedisReentrantLock.LOCK_KEY_PREFIX + MqIdempotentConsumeSupport.CONSUME_LOCK_PREFIX
                + dedup);
        redis.delete(RedisReentrantLock.LOCK_KEY_PREFIX + MqIdempotentConsumeSupport.CONSUME_LOCK_PREFIX
                + dedup + ":hb");
        redis.delete("idempotent:" + dedup);
    }

    @Test
    void crashedLockHolder_redeliveryIsNackedForRequeueNotAckedAsDuplicate() {
        String extractToken = UUID.randomUUID().toString();
        long docId = DOC_BASE + 21;
        String dedup = dedupKey(docId, extractToken);
        String deadHolderToken = UUID.randomUUID().toString();
        try {
            // Forensic shape of a crash: the lock survived its holder (lease
            // still running) while the heartbeat, whose ttl is one renew
            // interval, already expired — plant the lock hash (reentrant
            // shape: field=holder token), plant no heartbeat.
            redis.opsForHash().put(
                    RedisReentrantLock.LOCK_KEY_PREFIX + MqIdempotentConsumeSupport.CONSUME_LOCK_PREFIX
                            + dedup, deadHolderToken, "1");
            redis.expire(
                    RedisReentrantLock.LOCK_KEY_PREFIX + MqIdempotentConsumeSupport.CONSUME_LOCK_PREFIX
                            + dedup, 60, java.util.concurrent.TimeUnit.SECONDS);

            ExtractCallCounter service = new ExtractCallCounter();
            CoreGateway gateway = mock(CoreGateway.class);
            AiDocExtractConsumer consumer = harness(service, new RedisReentrantLock(redis), gateway);

            assertThrows(ImmediateRequeueAmqpException.class,
                    () -> consumer.onMessage(new AiDocExtractMessage(docId, 1L, 1L, null, extractToken)),
                    "lock held + heartbeat dead must nack back to the queue, never ack");
            assertEquals(0, service.calls.get(), "requeue path must not execute the extraction");
            // segment 3: the consumer now reads the in-flight window once per
            // delivery (falls back to 2x lease when core is unreachable) —
            // the requeue path still writes no drop audit at all.
            verify(gateway, never()).writeDuplicateDrop(any(), anyLong(), any(), any());
        } finally {
            cleanup(dedup);
        }
    }

    @Test
    void liveLockHolder_redeliveryKeepsPlainDuplicateSemantics() {
        String extractToken = UUID.randomUUID().toString();
        long docId = DOC_BASE + 22;
        String dedup = dedupKey(docId, extractToken);
        String liveHolderToken = UUID.randomUUID().toString();
        try {
            // Warm holder: lock present AND heartbeat refreshed (renewal-enabled
            // run in flight) — today's ack-as-duplicate behavior must not drift.
            String lockKey = RedisReentrantLock.LOCK_KEY_PREFIX
                    + MqIdempotentConsumeSupport.CONSUME_LOCK_PREFIX + dedup;
            redis.opsForHash().put(lockKey, liveHolderToken, "1");
            redis.expire(lockKey, 60, java.util.concurrent.TimeUnit.SECONDS);
            redis.opsForValue().set(lockKey + ":hb", liveHolderToken, 60, java.util.concurrent.TimeUnit.SECONDS);

            ExtractCallCounter service = new ExtractCallCounter();
            CoreGateway gateway = mock(CoreGateway.class);
            AiDocExtractConsumer consumer = harness(service, new RedisReentrantLock(redis), gateway);

            assertDoesNotThrow(
                    () -> consumer.onMessage(new AiDocExtractMessage(docId, 1L, 1L, null, extractToken)),
                    "live holder keeps the ack path — no requeue, no exception");
            assertEquals(0, service.calls.get(), "duplicate must not re-execute the extraction");
        } finally {
            cleanup(dedup);
        }
    }

    /**
 * segment 3 takeover shape: the crashed run's {@code E:} stamp survived while
 * the consume lock is already gone (lease expired / orderly release). The
 * old wiring acked this delivery as duplicate_drop and the task hung up to
 * 24h; the takeover clears the orphan, extracts for real once and settles
 * the replay anchor — with zero drop audit written.
 */
    @Test
    void orphanInFlightStamp_consumerTakeoverExecutesInsteadOfDropAudit() {
        String extractToken = UUID.randomUUID().toString();
        long docId = DOC_BASE + 23;
        String dedup = dedupKey(docId, extractToken);
        try {
            redis.opsForValue().set("idempotent:" + dedup, "E:ghost-" + extractToken,
                    60, java.util.concurrent.TimeUnit.SECONDS);

            ExtractCallCounter service = new ExtractCallCounter();
            CoreGateway gateway = mock(CoreGateway.class);
            AiDocExtractConsumer consumer = harness(service, new RedisReentrantLock(redis), gateway);

            assertDoesNotThrow(() -> consumer.onMessage(
                    new AiDocExtractMessage(docId, 1L, 1L, null, extractToken)),
                    "orphan E: must be taken over, not thrown, not nacked");
            assertEquals(1, service.calls.get(),
                    "takeover must run the extraction exactly once for real");
            verify(gateway, never()).writeDuplicateDrop(any(), anyLong(), any(), any());
            String settled = redis.opsForValue().get("idempotent:" + dedup);
            assertTrue(settled != null && settled.startsWith("D:"),
                    "takeover settles the replay anchor, was " + settled);
        } finally {
            cleanup(dedup);
        }
    }
}
