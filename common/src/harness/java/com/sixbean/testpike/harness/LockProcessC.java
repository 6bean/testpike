package com.sixbean.testpike.harness;

import com.sixbean.testpike.common.lock.RedisReentrantLock;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * <p>argv: [crashKey]
 *
 */
public final class LockProcessC {

    private static final long CRASH_LEASE_MILLIS = 4000;

    private LockProcessC() {
    }

    public static void main(String[] args) {
        String crashKey = args[0];
        String owner = "procC-" + ProcessHandle.current().pid();

        StringRedisTemplate redis = LockHarnessSupport.connect();
        RedisReentrantLock lock = new RedisReentrantLock(redis);

        if (!lock.tryLock(crashKey, owner, CRASH_LEASE_MILLIS)) {
            LockHarnessSupport.fail("C.tryLock", "崩溃锁取锁失败");
        }
        LockHarnessSupport.ok("C_LOCKED_EXITING_DIRTY");
        System.exit(0);
    }
}
