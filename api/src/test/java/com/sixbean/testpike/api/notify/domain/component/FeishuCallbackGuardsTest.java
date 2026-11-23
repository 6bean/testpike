package com.sixbean.testpike.api.notify.domain.component;

import com.sixbean.testpike.api.ApiApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = ApiApplication.class)
class FeishuCallbackGuardsTest {

    @Autowired
    private StringRedisTemplate redis;

    private final String fingerprint = UUID.randomUUID().toString();

    @AfterEach
    void clean() {
        redis.delete(NonceReplayGuard.KEY_PREFIX + "n-" + fingerprint);
        redis.delete(CallbackRateLimiter.KEY_PREFIX + "ip-" + fingerprint);
    }

    @Test
    void nonce首发通过_二次重放拒绝_TTL覆盖时间窗() {
        NonceReplayGuard guard = new NonceReplayGuard(redis, () -> NonceReplayGuard.DEFAULT_TTL_SECONDS);
        String nonce = "n-" + fingerprint;
        assertTrue(guard.firstSeen(nonce), "首发应放行");
        assertFalse(guard.firstSeen(nonce), "同 nonce 二次=重放应拒");
        Long ttl = redis.getExpire(NonceReplayGuard.KEY_PREFIX + nonce);
        assertTrue(ttl != null && ttl > 0 && ttl <= 600,
                "nonce TTL 须覆盖 ±5min 时间窗（600s 内），实测 " + ttl);
    }

    @Test
    void 空nonce拒绝() {
        assertFalse(new NonceReplayGuard(redis, () -> NonceReplayGuard.DEFAULT_TTL_SECONDS).firstSeen(null));
        assertFalse(new NonceReplayGuard(redis, () -> NonceReplayGuard.DEFAULT_TTL_SECONDS).firstSeen(""));
    }

    @Test
    void 限流窗口内放行_超限拒绝含等待指引() {
        CallbackRateLimiter limiter = new CallbackRateLimiter(redis, () -> 3, () -> CallbackRateLimiter.DEFAULT_WINDOW_SECONDS);
        String ip = "ip-" + fingerprint;
        for (int i = 1; i <= 3; i++) {
            assertTrue(limiter.tryAcquire(ip).allowed(), "第 " + i + " 次应放行");
        }
        CallbackDecision blocked = limiter.tryAcquire(ip);
        assertFalse(blocked.allowed(), "第 4 次必须拒");
        assertTrue(blocked.message().contains("秒"), "人话须含等待指引: " + blocked.message());
        assertEquals("4", redis.opsForValue().get(CallbackRateLimiter.KEY_PREFIX + ip));
    }

    @Test
    void 限额热更_每次现读supplier() {
        String ip = "ip-" + fingerprint + "-hot";
        java.util.concurrent.atomic.AtomicInteger limit = new java.util.concurrent.atomic.AtomicInteger(2);
        CallbackRateLimiter limiter = new CallbackRateLimiter(redis, limit::get, () -> CallbackRateLimiter.DEFAULT_WINDOW_SECONDS);
        assertTrue(limiter.tryAcquire(ip).allowed());
        assertTrue(limiter.tryAcquire(ip).allowed());
        assertFalse(limiter.tryAcquire(ip).allowed());
        limit.set(5);
        assertTrue(limiter.tryAcquire(ip).allowed(), "阈值上调后同窗口应恢复放行（现读热更）");
        redis.delete(CallbackRateLimiter.KEY_PREFIX + ip);
    }
}
