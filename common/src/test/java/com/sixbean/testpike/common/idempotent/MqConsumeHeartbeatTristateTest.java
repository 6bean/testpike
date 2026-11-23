package com.sixbean.testpike.common.idempotent;

import com.sixbean.testpike.common.lock.RedisReentrantLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Heartbeat tri-state dedup coverage for the MQ consume guard .
 * <p>Why this exists: a crashed holder keeps the consume lock until the long
 * lease expires, while its business run is already dead. Redeliveries that only
 * ask "is the lock held?" ack the message as DUPLICATE and the task hangs
 * forever (observed: extraction stuck 38 minutes after a restart, the
 * sweeper eventually reaped it). The liveness heartbeat splits "held" into
 * alive (skip, zero drift with today's behavior) and dead (requeue instead of
 * dropping the message).
 * <p>L2 connects to the real host Redis on 6379 — lease/heartbeat expiry is
 * the subject under test (mocking Redis proves nothing), same posture as
 * MqConsumeLeaseRenewalTest. Keys carry a per-run UUID prefix and are deleted
 * in finally so the suite never leaves residue.
 */
class MqConsumeHeartbeatTristateTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    private static String testKey(String name) {
        return "test:mq-hb:" + RUN_ID + ":" + name;
    }

    /** Business key as callers hand it to RedisReentrantLock (prefixed once inside). */
    private static String businessKey(String dedupKey) {
        return MqIdempotentConsumeSupport.CONSUME_LOCK_PREFIX + dedupKey;
    }

    /** Raw storage key of the lock hash (LOCK_KEY_PREFIX + business key). */
    private static String fullLockKey(String dedupKey) {
        return RedisReentrantLock.LOCK_KEY_PREFIX + businessKey(dedupKey);
    }

    /** Raw storage key of the liveness heartbeat (lock key + ":hb" by contract). */
    private static String fullHeartbeatKey(String dedupKey) {
        return fullLockKey(dedupKey) + ":hb";
    }

    private static void forceClean(String dedupKey) {
        redis.delete(fullLockKey(dedupKey));
        redis.delete(fullHeartbeatKey(dedupKey));
    }

    /**
     * Dead-holder spec: the lock is held but no live process renews its
     * heartbeat (crashed holder) — the redelivery must come back as
     * HEARTBEAT_DEAD_REQUEUE with the business call untouched, and once the
     * zombie lock is reaped a later delivery really executes (PROCESSED).
     */
    @Test
    void deadLockHeartbeatRequeuesInsteadOfDroppingTheMessage() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(lock);
        String dedupKey = testKey("dead-holder");
        try {
            // Simulate a crashed predecessor: lock present for the full lease,
            // heartbeat absent (its process died and the hb ttl expired).
            assertTrue(lock.tryLock(businessKey(dedupKey), "ghost-" + RUN_ID, 60_000));

            AtomicBoolean executed = new AtomicBoolean(false);
            String token = UUID.randomUUID().toString();
            ConsumeOutcome outcome = support.consumeOnce(dedupKey, token,
                    () -> {
                        executed.set(true);
                        return token;
                    }, 60_000, 100);

            assertEquals(ConsumeOutcome.HEARTBEAT_DEAD_REQUEUE, outcome,
                    "held lock with dead heartbeat must requeue, not ack a duplicate drop");
            assertFalse(executed.get(), "requeue path must not touch the business call");

            // After the zombie lock is reaped the same delivery really runs.
            redis.delete(fullLockKey(dedupKey));
            String freshToken = UUID.randomUUID().toString();
            ConsumeOutcome second = support.consumeOnce(dedupKey, freshToken,
                    () -> freshToken, 60_000, 100);
            assertEquals(ConsumeOutcome.PROCESSED, second);
            assertFalse(Boolean.TRUE.equals(redis.hasKey(fullHeartbeatKey(dedupKey))),
                    "finally must clear the heartbeat once the run settles");
        } finally {
            forceClean(dedupKey);
        }
    }

    /**
     * Live-holder spec: while a renewal-enabled run is executing (watchdog
     * keeps the heartbeat warm), a concurrent redelivery of the same message
     * still resolves to DUPLICATE with zero second execution — the tri-state
     * split must not weaken today's skip semantics.
     */
    @Test
    void liveHeartbeatKeepsRedeliveryOnTheDuplicateSkipPath() throws Exception {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(lock);
        String dedupKey = testKey("live-holder");
        try {
            String firstToken = UUID.randomUUID().toString();
            AtomicReference<ConsumeOutcome> firstOutcome = new AtomicReference<>();
            Thread worker = new Thread(() -> firstOutcome.set(support.consumeOnce(
                    dedupKey, firstToken,
                    () -> {
                        try {
                            Thread.sleep(600);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return firstToken;
                    },
                    60_000, 80)));
            worker.start();

            long deadline = System.currentTimeMillis() + 2_000;
            while (!lock.heartbeatAlive(businessKey(dedupKey))
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(lock.heartbeatAlive(businessKey(dedupKey)),
                    "acquire must write the heartbeat immediately (ttl=renewInterval)");

            AtomicBoolean secondExecuted = new AtomicBoolean(false);
            String secondToken = UUID.randomUUID().toString();
            ConsumeOutcome second = support.consumeOnce(dedupKey, secondToken,
                    () -> {
                        secondExecuted.set(true);
                        return secondToken;
                    }, 60_000, 100);

            assertEquals(ConsumeOutcome.DUPLICATE, second,
                    "live heartbeat means the holder runs for real — redelivery skips");
            assertFalse(secondExecuted.get());

            worker.join(5_000);
            assertEquals(ConsumeOutcome.PROCESSED, firstOutcome.get());
        } finally {
            forceClean(dedupKey);
        }
    }

    /**
     * Legacy zero-drift spec: renewal-disabled callers never write a heartbeat
     * and never consult one — a held lock stays plain DUPLICATE even though no
     * hb key exists (short-lived consumers keep today's ack-skip semantics,
     * no requeue storm).
     */
    @Test
    void renewalDisabledNeverWritesNorConsultsTheHeartbeat() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(lock);
        String dedupKey = testKey("legacy");
        try {
            // A successful legacy run must not leave a heartbeat behind.
            String token = UUID.randomUUID().toString();
            assertEquals(ConsumeOutcome.PROCESSED, support.consumeOnce(dedupKey, token,
                    () -> token, 60_000));
            assertFalse(Boolean.TRUE.equals(redis.hasKey(fullHeartbeatKey(dedupKey))),
                    "renewal-disabled acquire writes no heartbeat (zero drift)");

            // Held lock without any heartbeat + legacy caller = DUPLICATE, not
            // HEARTBEAT_DEAD_REQUEUE: the split only exists for renewal-enabled runs.
            assertTrue(lock.tryLock(businessKey(dedupKey), "ghost-" + RUN_ID, 60_000));
            String otherToken = UUID.randomUUID().toString();
            AtomicBoolean executed = new AtomicBoolean(false);
            ConsumeOutcome outcome = support.consumeOnce(dedupKey, otherToken,
                    () -> {
                        executed.set(true);
                        return otherToken;
                    }, 60_000);
            assertEquals(ConsumeOutcome.DUPLICATE, outcome,
                    "legacy path must stay plain-DUPLICATE even with no heartbeat");
            assertFalse(executed.get());
        } finally {
            forceClean(dedupKey);
        }
    }
}
