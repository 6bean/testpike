package com.sixbean.testpike.api.notify.domain.component;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.function.LongSupplier;


public class NonceReplayGuard {

    
    public static final String KEY_PREFIX = "notify:cb:nonce:";

    
    public static final String TTL_KEY = "notify.nonce-ttl-seconds";

    
    public static final long DEFAULT_TTL_SECONDS = 600L;

    private final StringRedisTemplate redis;
    private final LongSupplier ttlSeconds;

    public NonceReplayGuard(StringRedisTemplate redis, LongSupplier ttlSeconds) {
        this.redis = redis;
        this.ttlSeconds = ttlSeconds;
    }

    
    public boolean firstSeen(String nonce) {
        if (nonce == null || nonce.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(KEY_PREFIX + nonce, "1",
                Duration.ofSeconds(ttlSeconds.getAsLong())));
    }
}
