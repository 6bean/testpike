package com.sixbean.testpike.harness;

import com.sixbean.testpike.common.lock.RedisReentrantLock;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * <p>argv: [lockKey]
 *
 */
public final class LockProcessB {

    private static final long LEASE_MILLIS = 3000;

    private LockProcessB() {
    }

    public static void main(String[] args) throws Exception {
        String key = args[0];
        String owner = "procB-" + ProcessHandle.current().pid();

        StringRedisTemplate redis = LockHarnessSupport.connect();
        RedisReentrantLock lock = new RedisReentrantLock(redis);

        Thread.sleep(300);
        if (lock.tryLock(key, owner, LEASE_MILLIS)) {
            lock.unlock(key, owner);
            LockHarnessSupport.fail("B.tryLockDenied", "A 进程持锁期间 B 竟然取锁成功（互斥失效）");
        }
        LockHarnessSupport.ok("B_TRYLOCK_DENIED");

        long deadline = System.currentTimeMillis() + 15_000;
        while (lock.isLocked(key) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        if (!lock.tryLock(key, owner, LEASE_MILLIS)) {
            LockHarnessSupport.fail("B.acquireAfterRelease", "A 已释放但 B 取锁仍失败");
        }
        int held = lock.holdCount(key, owner);
        if (held != 1) {
            LockHarnessSupport.fail("B.holdCount", "取锁后计数=" + held + "（应为1）");
        }
        if (!lock.unlock(key, owner)) {
            LockHarnessSupport.fail("B.unlock", "属主释放被拒");
        }
        if (lock.isLocked(key)) {
            LockHarnessSupport.fail("B.keyGone", "释放后锁键仍存在");
        }
        LockHarnessSupport.ok("B_ACQUIRED_AFTER_RELEASE");
        LockHarnessSupport.ok("B_EXIT_0");
    }
}
