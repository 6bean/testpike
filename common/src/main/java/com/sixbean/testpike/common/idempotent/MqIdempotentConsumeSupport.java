package com.sixbean.testpike.common.idempotent;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.EntityGoneSignals;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * <ul>
 * </ul>
 *
 */
public class MqIdempotentConsumeSupport {

    private static final Logger log = LoggerFactory.getLogger(MqIdempotentConsumeSupport.class);

    
    public static final String CONSUME_LOCK_PREFIX = "mq-consume:";

    private final RedisReentrantLock lock;
    /**
 * the idempotent aspect behind the proxied onceCall, if
 * the host wired one. Null = legacy wiring, takeover disabled (today's
 * plain-DUPLICATE ack-skip stays byte-identical).
 */
    private final IdempotentAspect inFlightMarker;

    public MqIdempotentConsumeSupport(RedisReentrantLock lock) {
        this(lock, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MqIdempotentConsumeSupport(RedisReentrantLock lock, IdempotentAspect inFlightMarker) {
        this.lock = lock;
        this.inFlightMarker = inFlightMarker;
    }

    
    public ConsumeOutcome consumeOnce(String dedupKey, String token,
                                      Supplier<String> onceCall, long leaseMillis) {
        return consumeOnce(dedupKey, token, onceCall, leaseMillis, 0L);
    }

    /**
 * Idempotent single consumption with in-flight lease renewal (AP batch
 * renewal segment). A business call may outlive the original lease (the F2
 * extraction chain's worst-case LLM rounds drift past the lease calibrated
 * for typical runs). Without renewal the lock silently expires mid-run and a
 * redelivered copy of the same message acquires it and re-executes — double
 * LLM spend. When renewIntervalMillis is positive a watchdog thread keeps
 * the lease alive (renewing under the same owner token through the atomic
 * Lua renew primitive) until the call settles; non-positive keeps the
 * legacy single-lease behavior for short-lived consumers (no extra thread).
 *
 * @param renewIntervalMillis renewal cadence (lease/3 is the working default);
 * crash self-healing is unchanged — a dead process
 * takes its watchdog down and the lock expires
 * within one interval plus one lease at the latest
 */
    public ConsumeOutcome consumeOnce(String dedupKey, String token,
                                      Supplier<String> onceCall, long leaseMillis,
                                      long renewIntervalMillis) {
        String lockKey = CONSUME_LOCK_PREFIX + dedupKey;
        boolean renewalEnabled = renewIntervalMillis > 0;
        if (!lock.tryLock(lockKey, token, leaseMillis)) {
            // tri-state (renewal-enabled runs only): lock held but the
            // holder's liveness heartbeat is dead means that holder crashed —
            // acking a duplicate drop here would hang the task for one full
            // lease with nobody working it. Signal the consumer to requeue.
            if (renewalEnabled && !lock.heartbeatAlive(lockKey)) {
                log.info("MQ consume lock held but heartbeat dead (holder presumed "
                        + "crashed) -> requeue dedupKey={}", dedupKey);
                return ConsumeOutcome.HEARTBEAT_DEAD_REQUEUE;
            }
            log.info("MQ 消费在途重复（锁被持有，跳过）dedupKey={}", dedupKey);
            return ConsumeOutcome.DUPLICATE;
        }
        // capture the foreign heartbeat owner (if any) before
        // our own stamp overwrites it — the orphan-marker takeover below may
        // only run when no foreign live holder exists.
        String preexistingHbOwner = renewalEnabled ? lock.heartbeatOwner(lockKey) : null;
        if (renewalEnabled) {
            // Fresh ownership stamp: ttl=renewInterval so a killed holder is
            // detected within roughly one interval instead of one full lease.
            lock.heartbeat(lockKey, token, renewIntervalMillis);
        }
        Thread leaseKeeper = renewalEnabled
                ? startLeaseKeeper(lockKey, token, leaseMillis, renewIntervalMillis)
                : null;
        try {
            String echoed;
            try {
                echoed = onceCall.get();
            } catch (ServiceException e) {
                if (e.getCode() != null && e.getCode() == BizErrorCode.IDEMPOTENT_DUPLICATE) {
                    // we hold the consume lock yet the E: marker
                    // says someone else is in flight — that stamp is an orphan of a
                    // crashed predecessor. When the predecessor is proven dead (no
                    // foreign heartbeat at acquire) and the host wired the aspect,
                    // clear the orphan and run the business call once for real.
                    // Anything else keeps today's ack-skip (legacy wiring unchanged).
                    if (tryOrphanTakeover(dedupKey, renewalEnabled, preexistingHbOwner, token)) {
                        log.info("MQ consume orphan in-flight marker taken over, "
                                + "retrying once dedupKey={}", dedupKey);
                        try {
                            echoed = onceCall.get();
                        } catch (ServiceException retry) {
                            if (retry.getCode() != null
                                    && retry.getCode() == BizErrorCode.IDEMPOTENT_DUPLICATE) {
                                log.info("MQ consume still in-flight after takeover "
                                        + "(40903 skip) dedupKey={}", dedupKey);
                                return ConsumeOutcome.DUPLICATE;
                            }
                            throw retry;
                        }
                    } else {
                        log.info("MQ 消费幂等在途（40903 转跳过）dedupKey={}", dedupKey);
                        return ConsumeOutcome.DUPLICATE;
                    }
                } else if (EntityGoneSignals.matches(e)) {
                    // already gone can never be processed by a retry — rethrow would burn the
                    // container's 3 retries and land a hopeless row in the dead-letter ledger
                    // (ENTITY_GONE is hard-rejected by the replay gate anyway). Turn it into a
                    // distinguishable outcome so the consumer writes a skip audit and acks.
                    log.info("MQ 消费实体已删除降噪（跳过审计+ack，禁死信）dedupKey={} reason={}",
                            dedupKey, e.getMessage());
                    return ConsumeOutcome.ENTITY_GONE_SKIPPED;
                } else {
                    throw e;
                }
            }
            if (!token.equals(echoed)) {
                log.info("MQ 消费重复（已完成回放旧结果，跳过）dedupKey={}", dedupKey);
                return ConsumeOutcome.DUPLICATE;
            }
            return ConsumeOutcome.PROCESSED;
        } finally {
            if (leaseKeeper != null) {
                leaseKeeper.interrupt();
            }
            lock.unlock(lockKey, token);
            if (renewalEnabled) {
                // Order matters : stamp removed after release,
                // del-if-owner so a slow departing holder can never erase the
                // heartbeat of whoever grabbed the lock in the meantime.
                lock.clearHeartbeat(lockKey, token);
            }
        }
    }

    /**
 * takeover decision: renewal-enabled wiring with the
 * aspect assembled, and no foreign live heartbeat present at acquire
 * (a warm heartbeat stamped by someone else means that holder is still
 * running without the lock — takeover would double-execute). True only
 * when an {@code E:} stamp was actually erased; the {@code D:} replay
 * anchor is never touched.
 */
    private boolean tryOrphanTakeover(String dedupKey, boolean renewalEnabled,
                                      String preexistingHbOwner, String token) {
        if (!renewalEnabled || inFlightMarker == null) {
            return false;
        }
        if (preexistingHbOwner != null && !preexistingHbOwner.equals(token)) {
            return false;
        }
        return inFlightMarker.clearOrphanInFlight(dedupKey);
    }

    /**
     * Lease watchdog: renews the consume lock every interval while the business
     * call runs. Exits when the call settles (caller interrupts) or the lock is
     * no longer ours (lost/expired — renew returns false; logged once, then the
     * call keeps running and dedup falls back to the inner idempotent marker).
     * Plain daemon thread, no scheduler framework: one per in-flight message,
     * zero cost for renewal-disabled callers.
     */
    private Thread startLeaseKeeper(String lockKey, String token,
                                    long leaseMillis, long renewIntervalMillis) {
        Thread keeper = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(renewIntervalMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!lock.renew(lockKey, token, leaseMillis)) {
                    log.warn("消费锁续约失败（锁已失/非属主），后续去重依赖幂等标记 lockKey={}",
                            lockKey);
                    return;
                }
                // Same success gates the liveness stamp : renew proves
                // ownership, so touching hb here needs no extra ownership check
                // and redeliveries keep seeing it warm.
                lock.heartbeat(lockKey, token, renewIntervalMillis);
            }
        }, "mq-consume-lease-keeper");
        keeper.setDaemon(true);
        keeper.start();
        return keeper;
    }
}
