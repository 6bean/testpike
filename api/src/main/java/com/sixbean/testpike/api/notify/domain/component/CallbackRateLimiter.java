package com.sixbean.testpike.api.notify.domain.component;

import com.sixbean.testpike.common.ratelimit.FixedWindowRedisCounter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;


@Component
public class CallbackRateLimiter {

    
    public static final String KEY_PREFIX = "notify:cb:ratelimit:";

 
    public static final String WINDOW_KEY = "ratelimit.callback-window-seconds";

    
    public static final long DEFAULT_WINDOW_SECONDS = 60L;

    private final FixedWindowRedisCounter counter;
    private final IntSupplier limitSupplier;
    private final LongSupplier windowSeconds;

    public CallbackRateLimiter(StringRedisTemplate redis, IntSupplier limitSupplier,
                               LongSupplier windowSeconds) {
        this.counter = new FixedWindowRedisCounter(redis);
        this.limitSupplier = limitSupplier;
        this.windowSeconds = windowSeconds;
    }

    
    public CallbackDecision tryAcquire(String ipKey) {
        String key = KEY_PREFIX + ipKey;
        long current = counter.hit(key, Duration.ofSeconds(windowSeconds.getAsLong()));
        int limit = Math.max(1, limitSupplier.getAsInt());
        if (current <= limit) {
            return CallbackDecision.allow();
        }
        int retryAfter = Math.max(1, counter.remainingSeconds(key));
        return CallbackDecision.deny(
                "飞书回调已达每分钟上限（" + limit + " 次），请约 " + retryAfter + " 秒后重试",
                retryAfter);
    }
}
