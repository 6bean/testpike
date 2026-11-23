package com.sixbean.testpike.api.mcp.domain.component;

import com.sixbean.testpike.common.ratelimit.FixedWindowRedisCounter;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;


public class McpRateLimiter {

    
    public static final String KEY_PREFIX = "mcp:ratelimit:";

 
    public static final String WINDOW_KEY = "ratelimit.mcp-window-seconds";

    
    public static final long DEFAULT_WINDOW_SECONDS = 60L;

    private final FixedWindowRedisCounter counter;
    private final IntSupplier limitSupplier;
    private final LongSupplier windowSeconds;

    public McpRateLimiter(StringRedisTemplate redis, IntSupplier limitSupplier,
                          LongSupplier windowSeconds) {
        this.counter = new FixedWindowRedisCounter(redis);
        this.limitSupplier = limitSupplier;
        this.windowSeconds = windowSeconds;
    }

    
    public McpRateLimitDecision tryAcquire(String tokenKey) {
        String key = KEY_PREFIX + tokenKey;
        long current = counter.hit(key, Duration.ofSeconds(windowSeconds.getAsLong()));
        int limit = Math.max(1, limitSupplier.getAsInt());
        if (current <= limit) {
            return McpRateLimitDecision.allow();
        }
        int retryAfter = Math.max(1, counter.remainingSeconds(key));
        return McpRateLimitDecision.deny(
                "MCP 调用已达每分钟上限（" + limit + " 次），请约 " + retryAfter
                        + " 秒后重试；如需更高额度请联系管理员调整 mcp.rate-limit/min",
                retryAfter);
    }
}
