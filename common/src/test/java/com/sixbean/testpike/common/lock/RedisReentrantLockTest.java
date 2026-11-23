package com.sixbean.testpike.common.lock;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class RedisReentrantLockTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    private final RedisReentrantLock lock = new RedisReentrantLock(redisTemplate());

    private static StringRedisTemplate redisTemplate() {
        return redis;
    }

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
        return "test:lock:" + RedisReentrantLockTest.class.getSimpleName()
                + ":" + RUN_ID + ":" + name;
    }

    private static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);
    private static final String OWNER_A = "owner-a-" + RUN_ID;
    private static final String OWNER_B = "owner-b-" + RUN_ID;


    @Test
    void sameOwnerReentrant_acquiresTwiceWithCountTwo() {
        String key = testKey("reentrant");
        assertTrue(lock.tryLock(key, OWNER_A, 30_000), "首次取锁应成功");
        assertTrue(lock.tryLock(key, OWNER_A, 30_000), "同属主重入应成功");
        assertEquals(2, lock.holdCount(key, OWNER_A), "重入计数应为 2");
        assertTrue(lock.unlock(key, OWNER_A), "重入后第一次释放应成功");
        assertEquals(1, lock.holdCount(key, OWNER_A), "释放一次后计数应降为 1");
        assertTrue(lock.isLocked(key), "计数未归零锁仍在");
        assertTrue(lock.unlock(key, OWNER_A), "第二次释放应成功");
        assertFalse(lock.isLocked(key), "计数归零锁应删除");
    }

    @Test
    void nonOwnerRelease_rejectedAndLockKept() {
        String key = testKey("wrong-owner-release");
        assertTrue(lock.tryLock(key, OWNER_A, 30_000));
        assertTrue(lock.tryLock(key, OWNER_A, 30_000));

        assertFalse(lock.unlock(key, OWNER_B), "非属主释放必须被拒绝");
        assertFalse(lock.unlock(key, "anonymous"), "随机属主释放同样拒绝");
        assertEquals(2, lock.holdCount(key, OWNER_A), "非法释放不得改变重入计数");
        assertTrue(lock.isLocked(key), "非法释放不得放锁");

        assertTrue(lock.unlock(key, OWNER_A));
        assertTrue(lock.unlock(key, OWNER_A));
    }

    @Test
    void otherOwnerCannotAcquire_whileHeld() {
        String key = testKey("exclusive");
        assertTrue(lock.tryLock(key, OWNER_A, 30_000));
        assertFalse(lock.tryLock(key, OWNER_B, 30_000), "他属主不可重入他人锁");
        assertEquals(0, lock.holdCount(key, OWNER_B), "他属主计数为 0");
        assertTrue(lock.unlock(key, OWNER_A));
        assertTrue(lock.tryLock(key, OWNER_B, 30_000), "释放后他属主可取");
        assertTrue(lock.unlock(key, OWNER_B));
    }

    @Test
    void unlockUnheldLock_returnsFalse() {
        assertFalse(lock.unlock(testKey("never-held"), OWNER_A), "无锁释放返回 false 非异常");
    }


    @Test
    void leaseExpiry_autoReleasesAndOtherCanAcquire() throws Exception {
        String key = testKey("lease-expiry");
        assertTrue(lock.tryLock(key, OWNER_A, 400));
        assertTrue(lock.isLocked(key));
        Thread.sleep(700);
        assertFalse(lock.isLocked(key), "PX 过期后锁应已消失");
        assertTrue(lock.tryLock(key, OWNER_B, 30_000), "过期恢复后可再取");
        assertTrue(lock.unlock(key, OWNER_B));
    }

    @Test
    void renewByOwner_extendsLease_nonOwnerRejected() throws Exception {
        String key = testKey("renew");
        assertTrue(lock.tryLock(key, OWNER_A, 600));
        Thread.sleep(350);
        assertTrue(lock.renew(key, OWNER_A, 600), "属主续期应成功");
        assertFalse(lock.renew(key, OWNER_B, 600), "非属主续期应拒绝");
        Thread.sleep(500);
        assertTrue(lock.isLocked(key), "续期生效后 600ms 租期内不应过期");
        assertTrue(lock.unlock(key, OWNER_A));
    }


    @Test
    void watchdog_keepsAliveWhileRunning_expiresAfterStop() throws Exception {
        String key = testKey("watchdog");
        assertTrue(lock.tryLock(key, OWNER_A, 600));
        LockWatchdog watchdog = new LockWatchdog(lock);
        watchdog.start(key, OWNER_A, 600);
        Thread.sleep(1500);
        assertTrue(lock.isLocked(key), "看门狗运行期间锁不得因租期到期释放");
        assertEquals(1, lock.holdCount(key, OWNER_A));
        watchdog.stop(key, OWNER_A);
        long deadline = System.currentTimeMillis() + 2000;
        while (lock.isLocked(key) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertFalse(lock.isLocked(key), "看门狗停止后锁应在租期内自动释放");
    }


    @Test
    void concurrentContention_exactlyOneWinner() throws Exception {
        String key = testKey("contention");
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger winners = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> winnerOwner =
                new java.util.concurrent.atomic.AtomicReference<>();
        for (int i = 0; i < threads; i++) {
            String owner = "racer-" + i + "-" + RUN_ID;
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    if (lock.tryLock(key, owner, 30_000)) {
                        winners.incrementAndGet();
                        winnerOwner.set(owner);
                        done.await();
                        lock.unlock(key, owner);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        ready.await();
        go.countDown();
        while (done.getCount() > 1) {
            Thread.sleep(50);
        }
        assertEquals(1, winners.get(), "并发抢锁只能一个赢家（原子 Lua 语义）");
        assertTrue(lock.isLocked(key), "赢家在途时锁必须持有");
        done.countDown();
        Thread.sleep(300);
        assertFalse(lock.isLocked(key), "赢家释放后无残留");
    }
}
