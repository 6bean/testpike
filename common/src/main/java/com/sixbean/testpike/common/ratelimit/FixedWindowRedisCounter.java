package com.sixbean.testpike.common.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;


public final class FixedWindowRedisCounter {

    private final StringRedisTemplate redis;

    public FixedWindowRedisCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    
    public long hit(String key, Duration window) {
        Long count = redis.opsForValue().increment(key);
        long current = count == null ? 1L : count;
        if (current == 1L) {
            redis.expire(key, window);
        }
        return current;
    }

    
    public int remainingSeconds(String key) {
        Long ttl = redis.getExpire(key);
        return ttl == null || ttl <= 0 ? 0 : ttl.intValue();
    }
}
