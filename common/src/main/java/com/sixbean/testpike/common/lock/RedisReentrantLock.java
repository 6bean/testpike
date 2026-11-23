package com.sixbean.testpike.common.lock;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * <ul>
 * </ul>
 *
 */
@Component
public class RedisReentrantLock {

    
    public static final String LOCK_KEY_PREFIX = "lock:";

    
    private static final DefaultRedisScript<Long> TRY_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[1]) == 0 then "
                    + "  redis.call('HSET', KEYS[1], ARGV[1], 1) "
                    + "  redis.call('PEXPIRE', KEYS[1], ARGV[2]) "
                    + "  return 1 "
                    + "elseif redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then "
                    + "  redis.call('HINCRBY', KEYS[1], ARGV[1], 1) "
                    + "  redis.call('PEXPIRE', KEYS[1], ARGV[2]) "
                    + "  return 2 "
                    + "else "
                    + "  return 0 "
                    + "end", Long.class);

    
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then "
                    + "  return 0 "
                    + "end "
                    + "local count = redis.call('HINCRBY', KEYS[1], ARGV[1], -1) "
                    + "if count <= 0 then "
                    + "  redis.call('HDEL', KEYS[1], ARGV[1]) "
                    + "  if redis.call('HLEN', KEYS[1]) == 0 then "
                    + "    redis.call('DEL', KEYS[1]) "
                    + "  end "
                    + "  return 2 "
                    + "end "
                    + "return 1", Long.class);

    
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then "
                    + "  redis.call('PEXPIRE', KEYS[1], ARGV[2]) "
                    + "  return 1 "
                    + "end "
                    + "return 0", Long.class);

    
    private static final DefaultRedisScript<Long> HOLD_COUNT_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('HGET', KEYS[1], ARGV[1]) "
                    + "if v == false then return 0 end "
                    + "return tonumber(v)", Long.class);

    private final StringRedisTemplate redis;

    public RedisReentrantLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    
    public boolean tryLock(String key, String ownerId, long leaseMillis) {
        Long code = redis.execute(TRY_LOCK_SCRIPT,
                Collections.singletonList(LOCK_KEY_PREFIX + key), ownerId, String.valueOf(leaseMillis));
        return code != null && (code == 1L || code == 2L);
    }

    
    public boolean unlock(String key, String ownerId) {
        Long code = redis.execute(UNLOCK_SCRIPT,
                Collections.singletonList(LOCK_KEY_PREFIX + key), ownerId);
        return code != null && code > 0L;
    }

 
    public boolean renew(String key, String ownerId, long leaseMillis) {
        Long code = redis.execute(RENEW_SCRIPT,
                Collections.singletonList(LOCK_KEY_PREFIX + key), ownerId, String.valueOf(leaseMillis));
        return code != null && code == 1L;
    }

 
    public boolean isLocked(String key) {
        return Boolean.TRUE.equals(redis.hasKey(LOCK_KEY_PREFIX + key));
    }

    
    public Long getLeaseRemainingMillis(String key) {
        return redis.getExpire(LOCK_KEY_PREFIX + key, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    
    public String ownerOf(String key) {
        var fields = redis.<String, Object>opsForHash().keys(LOCK_KEY_PREFIX + key);
        return fields.isEmpty() ? null : fields.iterator().next();
    }

 
    public int holdCount(String key, String ownerId) {
        Long count = redis.execute(HOLD_COUNT_SCRIPT,
                Arrays.asList(LOCK_KEY_PREFIX + key), ownerId);
        return count == null ? 0 : count.intValue();
    }

    /**
 * Del-if-owner heartbeat clear : a slow former holder reaching
 * its finally late must never wipe the heartbeat of whoever owns the lock
 * now — value mismatch keeps the live holder's stamp intact.
 * KEYS[1]=heartbeat key ARGV[1]=ownerId; 1=cleared, 0=absent/foreign.
 */
    private static final DefaultRedisScript<Long> CLEAR_HEARTBEAT_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "  redis.call('DEL', KEYS[1]) "
                    + "  return 1 "
                    + "end "
                    + "return 0", Long.class);

    /** Heartbeat storage-key suffix (: exposed so ExtractDedupKeys composes
 * the same grammar instead of re-spelling the literal — one source, two users). */
    public static final String HEARTBEAT_SUFFIX = ":hb";

    /** Storage key of the liveness heartbeat paired with a lock (lock:{key}:hb). */
    private static String heartbeatKey(String key) {
        return LOCK_KEY_PREFIX + key + HEARTBEAT_SUFFIX;
    }

    /**
 * Stamp the caller's liveness with a short TTL . Callers are
 * ownership-gated by construction: first write happens right after a
 * successful tryLock, later writes after a successful renew — both prove
 * the caller owns the lock, so a plain SET PX suffices and the stamp can
 * simply expire when the holder's process dies (that is the signal).
 *
 * @param ttlMillis heartbeat freshness window (lease/3-scale, far below the
 * lock lease: dead holders are discovered in one interval
 * instead of one full lease)
 */
    public void heartbeat(String key, String ownerId, long ttlMillis) {
        redis.opsForValue().set(heartbeatKey(key), ownerId, ttlMillis, TimeUnit.MILLISECONDS);
    }

    /** True while any live holder keeps the heartbeat warm (redelivery tri-state input). */
    public boolean heartbeatAlive(String key) {
        return Boolean.TRUE.equals(redis.hasKey(heartbeatKey(key)));
    }

    /**
 * Owner id currently stamped on the liveness heartbeat, null when absent.
 * the consume guard captures this BEFORE its own stamp so
 * an orphan in-flight marker is only taken over when no foreign live
 * holder owns the warm heartbeat.
 */
    public String heartbeatOwner(String key) {
        return redis.opsForValue().get(heartbeatKey(key));
    }

    /** Clear the heartbeat on orderly release; false = already expired or owned by another holder. */
    public boolean clearHeartbeat(String key, String ownerId) {
        Long code = redis.execute(CLEAR_HEARTBEAT_SCRIPT,
                Collections.singletonList(heartbeatKey(key)), ownerId);
        return code != null && code == 1L;
    }
}
