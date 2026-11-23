package com.sixbean.testpike.harness;

import com.sixbean.testpike.common.lock.LockWatchdog;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import org.springframework.data.redis.core.StringRedisTemplate;


public final class LockProcessA {

    private static final long LEASE_MILLIS = 3000;

    private LockProcessA() {
    }

    public static void main(String[] args) throws Exception {
        String key = args[0];
        String crashKey = args[1];
        String owner = "procA-" + ProcessHandle.current().pid();

        StringRedisTemplate redis = LockHarnessSupport.connect();
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        LockWatchdog watchdog = new LockWatchdog(lock);

        if (!lock.tryLock(key, owner, LEASE_MILLIS)) {
            LockHarnessSupport.fail("A.tryLock", "首次取锁失败");
        }
        LockHarnessSupport.ok("A_LOCKED");
        long lockedAt = System.currentTimeMillis();
        watchdog.start(key, owner, LEASE_MILLIS);

        long deadline = System.currentTimeMillis() + 10_000;
        while (!lock.isLocked(crashKey) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        if (!lock.isLocked(crashKey)) {
            LockHarnessSupport.fail("A.crashLockAppeared", "C 的锁键从未出现");
        }
        long t0 = System.currentTimeMillis();
        while (lock.isLocked(crashKey) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        long elapsed = System.currentTimeMillis() - t0;
        if (lock.isLocked(crashKey)) {
            LockHarnessSupport.fail("A.crashLockExpired", "崩溃锁 10s 未自动释放");
        }
        if (elapsed < 50 || elapsed > 5000) {
            LockHarnessSupport.fail("A.crashLockTiming", "释放耗时 " + elapsed + "ms 不在 (50,5000] 区间");
        }
        System.out.println("OK A_CRASH_RELEASED_MS=" + elapsed);

        long holdFloor = lockedAt + LEASE_MILLIS + 5000;
        while (System.currentTimeMillis() < holdFloor) {
            Thread.sleep(100);
        }
        if (!lock.isLocked(key)) {
            LockHarnessSupport.fail("A.watchdogAlive", "主锁超过租期被过期释放（看门狗未续租）");
        }
        LockHarnessSupport.ok("A_WATCHDOG_HELD");

        if (!lock.unlock(key, owner)) {
            LockHarnessSupport.fail("A.unlock", "属主释放被拒");
        }
        watchdog.stop(key, owner);
        if (watchdog.activeTasks() != 0) {
            LockHarnessSupport.fail("A.watchdogStop", "activeTasks=" + watchdog.activeTasks());
        }
        if (lock.holdCount(key, owner) != 0) {
            LockHarnessSupport.fail("A.holdCount", "释放后计数未归零");
        }
        LockHarnessSupport.ok("A_RELEASED");
        LockHarnessSupport.ok("A_EXIT_0");
        System.exit(0);
    }
}
