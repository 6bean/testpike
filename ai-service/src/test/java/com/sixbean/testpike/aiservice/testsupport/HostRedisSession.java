package com.sixbean.testpike.aiservice.testsupport;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Live host-Redis session for the L2 consumer tests that must exercise the
 * real local Redis (locks, heartbeats and idempotency keys are never faked).
 * Connect and release wiring is defined once here and consumed by every
 * mq consumer test instead of being copied per class.
 */
public final class HostRedisSession {

    private final LettuceConnectionFactory factory;
    private final StringRedisTemplate redis;

    private HostRedisSession(LettuceConnectionFactory factory, StringRedisTemplate redis) {
        this.factory = factory;
        this.redis = redis;
    }

    /** Opens a session against the host Redis on localhost:6379. */
    public static HostRedisSession connect() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        return new HostRedisSession(factory, redis);
    }

    public StringRedisTemplate template() {
        return redis;
    }

    /** Releases the underlying connection factory. */
    public void close() {
        factory.destroy();
    }
}
