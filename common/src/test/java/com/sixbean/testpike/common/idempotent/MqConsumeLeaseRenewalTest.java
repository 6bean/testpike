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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lease-renewal coverage for the MQ consume guard .
 * <p>Why this exists: the consume lock is taken with a fixed lease sized for the
 * worst-case run. When a run outlives the lease, the key silently expires and a
 * redelivered copy of the same message acquires the "free" lock and re-executes
 * the expensive business call (double LLM spend). The renewal variant keeps the
 * lease alive while the business call is still running, so the second delivery
 * still sees the lock held and skips.
 * <p>L2 connects to the real host Redis on 6379 — the lock lease semantics
 * themselves are the subject under test (mocking Redis here proves nothing),
 * same posture as RedisReentrantLockTest. Keys carry a per-run UUID prefix so
 * tests never collide.
 */
class MqConsumeLeaseRenewalTest {

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
        return "test:mq-renew:" + RUN_ID + ":" + name;
    }

    private static void forceDelete(String dedupKey) {
        redis.delete(RedisReentrantLock.LOCK_KEY_PREFIX
                + MqIdempotentConsumeSupport.CONSUME_LOCK_PREFIX + dedupKey);
    }

    /**
     * Renewal spec: a business call that outlives the original lease must keep
     * the lock held (renewed) the whole time, so a concurrent second delivery of
     * the same message is rejected, and the run finishes as PROCESSED.
     */
    @Test
    void longRunningCallKeepsLeaseAliveAndSecondDeliveryIsRejected() throws Exception {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(lock);
        String dedupKey = testKey("renewed");
        String token = UUID.randomUUID().toString();
        long leaseMillis = 300;

        AtomicBoolean secondSawLock = new AtomicBoolean(false);
        AtomicReference<ConsumeOutcome> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> outcome.set(support.consumeOnce(
                dedupKey, token,
                () -> {
                    try {
                        // Outlive the lease several times over: without renewal
                        // the key expires mid-run and the probe below acquires it.
                        Thread.sleep(900);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return token;
                },
                leaseMillis, 80)));
        worker.start();
        // Probe while the business call is running and past the raw lease:
        // renewal must keep the lock owned, so the retry copy cannot enter.
        Thread.sleep(500);
        secondSawLock.set(!lock.tryLock(
                MqIdempotentConsumeSupport.CONSUME_LOCK_PREFIX + dedupKey,
                "intruder-" + RUN_ID, leaseMillis));
        worker.join(5000);

        assertTrue(secondSawLock.get(), "lock must stay held via renewal while the call runs");
        assertEquals(ConsumeOutcome.PROCESSED, outcome.get());
        assertFalse(lock.isLocked(MqIdempotentConsumeSupport.CONSUME_LOCK_PREFIX + dedupKey),
                "lock must be released after the run settles");
    }

    /**
     * Baseline spec (the hole the renewal closes): with the lease-only variant,
     * a call that outlives the lease lets a second delivery acquire the expired
     * lock — proving the double-execution window is real, not theoretical.
     */
    @Test
    void leaseWithoutRenewalExpiresMidRunAndAllowsSecondAcquirer() throws Exception {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(lock);
        String dedupKey = testKey("expired");
        String token = UUID.randomUUID().toString();
        long leaseMillis = 200;
        String lockKey = MqIdempotentConsumeSupport.CONSUME_LOCK_PREFIX + dedupKey;

        AtomicReference<ConsumeOutcome> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> outcome.set(support.consumeOnce(
                dedupKey, token,
                () -> {
                    try {
                        Thread.sleep(600);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return token;
                },
                leaseMillis)));
        worker.start();
        Thread.sleep(400);
        boolean intruderAcquired = lock.tryLock(lockKey, "intruder-" + RUN_ID, leaseMillis);
        assertTrue(intruderAcquired, "raw lease must expire mid-run (documents the double-run hole)");
        assertNotEquals(null, lock.ownerOf(lockKey));

        worker.join(5000);
        assertEquals(ConsumeOutcome.PROCESSED, outcome.get());
        // The worker's finally-unlock belongs to the original owner and must not
        // clobber the intruder's fresh hold; clean both explicitly.
        redis.delete(RedisReentrantLock.LOCK_KEY_PREFIX + lockKey);
    }

    /**
     * Renewal-disabled spec: passing a non-positive renewal interval keeps the
     * legacy behavior for short-running consumers (no watchdog thread, lease
     * unchanged until unlock).
     */
    @Test
    void renewalDisabledKeepsLegacySingleShotSemantics() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(lock);
        String dedupKey = testKey("disabled");
        String token = UUID.randomUUID().toString();
        String lockKey = MqIdempotentConsumeSupport.CONSUME_LOCK_PREFIX + dedupKey;

        ConsumeOutcome first = support.consumeOnce(dedupKey, token, () -> token, 60_000, 0);
        assertEquals(ConsumeOutcome.PROCESSED, first);
        assertFalse(lock.isLocked(lockKey), "no renewal thread must linger after completion");

        // Idempotent replay of an already-completed key is still deduplicated by
        // the inner guard chain at consumer level; at support level the lock is
        // free again, so a fresh token echoes fresh execution.
        forceDelete(dedupKey);
    }
}
