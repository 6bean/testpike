package com.sixbean.testpike.common.idempotent;

import com.sixbean.testpike.common.lock.RedisReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Orphan in-flight marker takeover .
 * <p>Why this exists: the consume lock self-heals within one lease after a
 * crash, but the {@code E:} in-flight marker of the idempotent layer kept the
 * annotation-wide 24h window. A redelivery landing in that window acquired the
 * lock, tripped 40903 on the orphan marker and was acked as DUPLICATE — the
 * task hung for up to a full day with nobody working it (observed with the
 * consume lease and lets a renewal-enabled run whose holder heartbeat died
 * clear the orphan marker and execute for real.
 * <p>Two-directional discipline (R43): injected dead holder must re-execute,
 * live holder must keep skipping; the done marker {@code D:} is a replay
 * anchor and is never cleared here.
 * <p>L2 uses the real host Redis on 6379 (expiry semantics are the subject
 * under test), AspectJProxyFactory weaving, per-run UUID keys, finally-clean.
 */
class MqConsumeOrphanInFlightTakeoverTest {

    @RegisterExtension
    static final RedisTestHarness HARNESS = new RedisTestHarness();
    private static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);

    private static String businessKey(String name) {
        return "test:orphan-takeover:" + RUN_ID + ":" + name;
    }

    /** Raw storage key of the idempotent marker (prefix + business key). */
    private static String markerKey(String dedupKey) {
        return IdempotentAspect.KEY_PREFIX + dedupKey;
    }

    /** Lock storage key the consume guard derives from the dedup key. */
    private static String lockKey(String dedupKey) {
        return RedisReentrantLock.LOCK_KEY_PREFIX
                + MqIdempotentConsumeSupport.CONSUME_LOCK_PREFIX + dedupKey;
    }

    private static String heartbeatKey(String dedupKey) {
        return lockKey(dedupKey) + ":hb";
    }

    private static void forceClean(String dedupKey) {
        HARNESS.redis().delete(markerKey(dedupKey));
        HARNESS.redis().delete(lockKey(dedupKey));
        HARNESS.redis().delete(heartbeatKey(dedupKey));
    }

    private static IdempotentDemoService proxyWith(IdempotentDemoService target,
                                                   IdempotentAspect aspect) {
        AspectJProxyFactory pf = new AspectJProxyFactory(target);
        pf.addAspect(aspect);
        pf.setProxyTargetClass(true);
        return pf.getProxy();
    }

    /**
     * In-flight TTL spec: while a run is executing, the E: marker must carry
     * the caller-sized in-flight window (60s here), never the 24h done window;
     * after success the marker settles to D: with the full replay window.
     */
    @Test
    void inflightMarkerCarriesSizedTtlAndDoneMarkerKeepsReplayWindow() throws Exception {
        String dedupKey = businessKey("ttl-sizing");
        IdempotentAspect aspect = new IdempotentAspect(HARNESS.redis());
        IdempotentDemoService target = new IdempotentDemoService();
        IdempotentDemoService proxy = proxyWith(target, aspect);
        try {
            Thread worker = new Thread(() -> proxy.gatedEcho(dedupKey, 60L));
            worker.start();

            long deadline = System.currentTimeMillis() + 3_000;
            while (!Boolean.TRUE.equals(HARNESS.redis().hasKey(markerKey(dedupKey)))
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(Boolean.TRUE.equals(HARNESS.redis().hasKey(markerKey(dedupKey))),
                    "in-flight marker must be visible while the run executes");
            Long inflightExpire = HARNESS.redis().getExpire(markerKey(dedupKey));
            assertTrue(inflightExpire != null && inflightExpire > 0 && inflightExpire <= 60,
                    "in-flight marker TTL must equal the sized window (<=60s), was "
                            + inflightExpire + "s — the 24h default leaked into the E: state");

            worker.join(5_000);
            String settled = HARNESS.redis().opsForValue().get(markerKey(dedupKey));
            assertTrue(settled != null && settled.startsWith("D:"),
                    "success must settle the marker to the replay anchor, was " + settled);
            Long doneExpire = HARNESS.redis().getExpire(markerKey(dedupKey));
            assertTrue(doneExpire != null && doneExpire > 80_000,
                    "done marker keeps the annotation window (~24h), was " + doneExpire + "s");
        } finally {
            forceClean(dedupKey);
        }
    }

    /**
     * Dead-holder takeover: an orphan E: marker (lock free, no warm heartbeat
     * = predecessor crashed) must NOT park this delivery as DUPLICATE — the
     * guard clears the orphan and the business body really executes once.
     */
    @Test
    void orphanInFlightMarkerIsTakenOverAndTheDeliveryExecutes() {
        String dedupKey = businessKey("orphan-takeover");
        IdempotentAspect aspect = new IdempotentAspect(HARNESS.redis());
        IdempotentDemoService target = new IdempotentDemoService();
        IdempotentDemoService proxy = proxyWith(target, aspect);
        RedisReentrantLock lock = new RedisReentrantLock(HARNESS.redis());
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(lock, aspect);
        try {
            // Crashed predecessor's residue: in-flight marker only, no lock, no heartbeat.
            HARNESS.redis().opsForValue().set(markerKey(dedupKey), "E:ghost-" + RUN_ID,
                    java.time.Duration.ofSeconds(3600));

            AtomicBoolean executed = new AtomicBoolean(false);
            String token = UUID.randomUUID().toString();
            ConsumeOutcome outcome = support.consumeOnce(dedupKey, token,
                    () -> {
                        executed.set(true);
                        return proxy.echo(dedupKey) != null ? token : token;
                    }, 60_000, 200);

            assertEquals(ConsumeOutcome.PROCESSED, outcome,
                    "orphan E: with dead holder must be taken over, not acked DUPLICATE");
            assertTrue(executed.get(), "takeover must run the business body for real");
            assertEquals(1, target.invocations(),
                    "the proxied method executes exactly once after takeover");
            assertTrue(String.valueOf(HARNESS.redis().opsForValue().get(markerKey(dedupKey)))
                            .startsWith("D:"),
                    "settled success keeps the replay anchor");
        } finally {
            forceClean(dedupKey);
        }
    }

    /**
     * Live-holder refusal (opposite direction): E: marker plus a warm
     * heartbeat means somebody is still running without the lock (lost
     * renewal race) — takeover must refuse, outcome stays DUPLICATE and the
     * body never executes.
     */
    @Test
    void warmHeartbeatRefusesTakeoverAndKeepsTheDuplicateSkip() {
        String dedupKey = businessKey("live-refusal");
        IdempotentAspect aspect = new IdempotentAspect(HARNESS.redis());
        IdempotentDemoService target = new IdempotentDemoService();
        IdempotentDemoService proxy = proxyWith(target, aspect);
        RedisReentrantLock lock = new RedisReentrantLock(HARNESS.redis());
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(lock, aspect);
        try {
            HARNESS.redis().opsForValue().set(markerKey(dedupKey), "E:live-" + RUN_ID,
                    java.time.Duration.ofSeconds(3600));
            lock.heartbeat(MqIdempotentConsumeSupport.CONSUME_LOCK_PREFIX + dedupKey,
                    "live-" + RUN_ID, 30_000);

            AtomicBoolean executed = new AtomicBoolean(false);
            String token = UUID.randomUUID().toString();
            ConsumeOutcome outcome = support.consumeOnce(dedupKey, token,
                    () -> {
                        executed.set(true);
                        return proxy.echo(dedupKey) != null ? token : token;
                    }, 60_000, 200);

            assertEquals(ConsumeOutcome.DUPLICATE, outcome,
                    "warm heartbeat = a live holder exists — skip, never double-execute");
            assertEquals(0, target.invocations(),
                    "refusal path must never execute the business body");
        } finally {
            forceClean(dedupKey);
        }
    }

    /**
     * Zero-drift pin: hosts that wire the support without the aspect (legacy
     * single-arg constructor) keep today's plain DUPLICATE ack-skip even for
     * an orphan marker — the takeover only exists where it was assembled.
     */
    @Test
    void legacyWiringWithoutAspectKeepsPlainDuplicateBehavior() {
        String dedupKey = businessKey("legacy-no-aspect");
        IdempotentAspect aspect = new IdempotentAspect(HARNESS.redis());
        IdempotentDemoService target = new IdempotentDemoService();
        IdempotentDemoService proxy = proxyWith(target, aspect);
        RedisReentrantLock lock = new RedisReentrantLock(HARNESS.redis());
        MqIdempotentConsumeSupport legacy = new MqIdempotentConsumeSupport(lock);
        try {
            HARNESS.redis().opsForValue().set(markerKey(dedupKey), "E:ghost-" + RUN_ID,
                    java.time.Duration.ofSeconds(3600));
            String token = UUID.randomUUID().toString();
            ConsumeOutcome outcome = legacy.consumeOnce(dedupKey, token,
                    () -> proxy.echo(dedupKey) != null ? token : token, 60_000, 200);
            assertEquals(ConsumeOutcome.DUPLICATE, outcome,
                    "no cleaner wired = today's ack-skip, unchanged");
            assertEquals(0, target.invocations());
        } finally {
            forceClean(dedupKey);
        }
    }

    /**
     * Replay-anchor pin: takeover clears E: only. A completed D: marker is
     * never erased — late redeliveries keep replaying the first result.
     */
    @Test
    void clearOrphanPrimitiveNeverErasesTheDoneMarker() {
        String dedupKey = businessKey("done-anchor");
        IdempotentAspect aspect = new IdempotentAspect(HARNESS.redis());
        try {
            HARNESS.redis().opsForValue().set(markerKey(dedupKey), "D:\"result-json\"",
                    java.time.Duration.ofSeconds(3600));
            assertFalse(aspect.clearOrphanInFlight(dedupKey),
                    "D: is the replay anchor — clearOrphanInFlight must leave it");
            assertTrue(String.valueOf(HARNESS.redis().opsForValue().get(markerKey(dedupKey)))
                    .startsWith("D:"), "done marker value untouched");
            // And it really clears an E: marker when one exists.
            HARNESS.redis().opsForValue().set(markerKey(dedupKey), "E:ghost-" + RUN_ID,
                    java.time.Duration.ofSeconds(3600));
            assertTrue(aspect.clearOrphanInFlight(dedupKey),
                    "E: orphan must be clearable (the takeover primitive works both ways)");
            assertFalse(Boolean.TRUE.equals(HARNESS.redis().hasKey(markerKey(dedupKey))));
        } finally {
            forceClean(dedupKey);
        }
    }
}
