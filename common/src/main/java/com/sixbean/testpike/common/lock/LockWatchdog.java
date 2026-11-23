package com.sixbean.testpike.common.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


@Component
public class LockWatchdog {

    private static final Logger log = LoggerFactory.getLogger(LockWatchdog.class);

    private final RedisReentrantLock lock;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread t = new Thread(runnable, "lock-watchdog");
                t.setDaemon(true);
                return t;
            });

    
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public LockWatchdog(RedisReentrantLock lock) {
        this.lock = lock;
    }

    
    public void start(String key, String ownerId, long leaseMillis) {
        tasks.computeIfAbsent(taskId(key, ownerId), id -> scheduler.scheduleAtFixedRate(
                () -> renewQuietly(key, ownerId, leaseMillis),
                Math.max(leaseMillis / 3, 100),
                Math.max(leaseMillis / 3, 100),
                TimeUnit.MILLISECONDS));
    }

    
    public void stop(String key, String ownerId) {
        ScheduledFuture<?> future = tasks.remove(taskId(key, ownerId));
        if (future != null) {
            future.cancel(false);
        }
    }

 
    public int activeTasks() {
        return tasks.size();
    }

    private void renewQuietly(String key, String ownerId, long leaseMillis) {
        try {
            if (!lock.renew(key, ownerId, leaseMillis)) {
                log.warn("看门狗续租未命中（锁已失或非属主）key={} owner={}", key, ownerId);
            }
        } catch (RuntimeException e) {
            log.warn("看门狗续租异常（下轮重试）key={} err={}", key, e.getMessage());
        }
    }

    private static String taskId(String key, String ownerId) {
        return key + "\u0000" + ownerId;
    }
}
