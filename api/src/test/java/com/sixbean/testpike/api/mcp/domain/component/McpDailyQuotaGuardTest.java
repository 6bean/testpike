package com.sixbean.testpike.api.mcp.domain.component;

import com.sixbean.testpike.api.ApiApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Daily quota guard tests for governance key mcp.quota.calls-per-token-day.
 * <p>Real host Redis (multi-instance shared state must not be mocked, same
 * baseline as McpRateLimiterTest); random fingerprints keep test cases from
 * sharing a counter window, and every key is deleted after the case.
 */
@SpringBootTest(classes = ApiApplication.class)
class McpDailyQuotaGuardTest {

    @Autowired
    private StringRedisTemplate redis;

    /** The day part of the key, built the same way the guard builds it. */
    private String dayKey(String token) {
        return McpDailyQuotaGuard.KEY_PREFIX + token + ":" + LocalDate.now();
    }

    private final String token = "q-" + UUID.randomUUID();

    @AfterEach
    void clean() {
        redis.delete(dayKey(token));
    }

    @Test
    void 上限内放行_计数落当日键() {
        McpDailyQuotaGuard guard = new McpDailyQuotaGuard(redis, () -> 2);
        assertTrue(guard.tryAcquire(token).allowed(), "first call within cap must pass");
        assertTrue(guard.tryAcquire(token).allowed(), "second call within cap must pass");
        assertEquals("2", redis.opsForValue().get(dayKey(token)), "counter must live on the day key");
        Long ttl = redis.getExpire(dayKey(token));
        assertTrue(ttl != null && ttl > 0 && ttl <= 86400,
                "day-key TTL must expire by midnight, observed " + ttl);
    }

    @Test
    void 超上限拒绝_人话点名当前次数与键字面() {
        McpDailyQuotaGuard guard = new McpDailyQuotaGuard(redis, () -> 2);
        assertTrue(guard.tryAcquire(token).allowed());
        assertTrue(guard.tryAcquire(token).allowed());
        McpRateLimitDecision blocked = guard.tryAcquire(token);
        assertFalse(blocked.allowed(), "third call beyond cap 2 must be denied");
        assertTrue(blocked.message().contains("2"), "message must quote the cap: " + blocked.message());
        assertTrue(blocked.message().contains("3"), "message must quote the current count: " + blocked.message());
        assertTrue(blocked.message().contains(McpDailyQuotaGuard.QUOTA_KEY),
                "message must quote the governance key name: " + blocked.message());
        assertTrue(blocked.retryAfterSeconds() >= 1, "retryAfter must be at least 1 second");
    }

    @Test
    void 不同Token不共享配额() {
        McpDailyQuotaGuard guard = new McpDailyQuotaGuard(redis, () -> 1);
        String other = "q-" + UUID.randomUUID();
        try {
            assertTrue(guard.tryAcquire(other).allowed());
            assertFalse(guard.tryAcquire(other).allowed(), "same token second call must be denied");
            assertTrue(guard.tryAcquire(token).allowed(), "another token exhausted must not block this one");
        } finally {
            redis.delete(dayKey(other));
        }
    }

    @Test
    void 配额热更_上限改小即刻生效() {
        java.util.concurrent.atomic.AtomicInteger cap = new java.util.concurrent.atomic.AtomicInteger(3);
        McpDailyQuotaGuard guard = new McpDailyQuotaGuard(redis, cap::get);
        assertTrue(guard.tryAcquire(token).allowed());
        cap.set(1);
        assertFalse(guard.tryAcquire(token).allowed(),
                "the cap is read live on every call, a lowered value must apply immediately");
    }
}
