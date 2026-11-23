package com.sixbean.testpike.api.mcp.domain.component;

import com.sixbean.testpike.api.ApiApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = ApiApplication.class)
class McpRateLimiterTest {

    @Autowired
    private StringRedisTemplate redis;

 
    private McpRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new McpRateLimiter(redis, () -> 30, () -> McpRateLimiter.DEFAULT_WINDOW_SECONDS);
    }

    
    private final String token = "t-" + UUID.randomUUID();

    @AfterEach
    void clean() {
        redis.delete(McpRateLimiter.KEY_PREFIX + token);
    }

    @Test
    void 阈值内放行_计数落Redis窗口() {
        for (int i = 1; i <= 30; i++) {
            McpRateLimitDecision d = limiter.tryAcquire(token);
            assertTrue(d.allowed(), "第 " + i + " 次应放行: " + d.message());
        }
        assertEquals("30", redis.opsForValue().get(McpRateLimiter.KEY_PREFIX + token));
        Long ttl = redis.getExpire(McpRateLimiter.KEY_PREFIX + token);
        assertTrue(ttl != null && ttl > 0 && ttl <= 60, "窗口 TTL 应为秒级 60s 内，实测 " + ttl);
    }

    @Test
    void 第31次拒绝_人话含等待时长与上限() {
        for (int i = 0; i < 30; i++) {
            assertTrue(limiter.tryAcquire(token).allowed());
        }
        McpRateLimitDecision blocked = limiter.tryAcquire(token);
        assertFalse(blocked.allowed(), "第 31 次必须拒");
        assertTrue(blocked.message().contains("30"), "人话须含上限 30: " + blocked.message());
        assertTrue(blocked.message().contains("秒") || blocked.message().contains("重"),
                "人话须含等待/重试指引: " + blocked.message());
        assertTrue(blocked.retryAfterSeconds() >= 1, "retryAfter 至少 1 秒: " + blocked.retryAfterSeconds());
    }

    @Test
    void 只读态与认证失败不计数_仅到达工具面的请求计数() {
        String other = "t-" + UUID.randomUUID();
        for (int i = 0; i < 30; i++) {
            limiter.tryAcquire(other);
        }
        assertTrue(limiter.tryAcquire(token).allowed(), "另一 Token 达阈值不得影响本 Token");
        redis.delete(McpRateLimiter.KEY_PREFIX + other);
    }

    @Test
    void 预算可热更_上限改小即刻生效() {
        McpRateLimiter tight = new McpRateLimiter(redis, () -> 2, () -> McpRateLimiter.DEFAULT_WINDOW_SECONDS);
        String t2 = "t-" + UUID.randomUUID();
        assertTrue(tight.tryAcquire(t2).allowed());
        assertTrue(tight.tryAcquire(t2).allowed());
        assertFalse(tight.tryAcquire(t2).allowed(), "预算热更=每次现读 supplier，须 3 次即拒");
        redis.delete(McpRateLimiter.KEY_PREFIX + t2);
    }
}
