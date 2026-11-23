package com.sixbean.testpike.common.idempotent;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Shared host-Redis L2 harness .
 * <p>The per-class @BeforeAll connect/destroy block was copy-pasted across the
 * consume-guard test family (the duplication gate ledger tracks it as
 * registered debt); every NEW test must consume this class instead of adding
 * another copy (R40). The debt sweep scheduled with folds the existing
 * identical blocks in here as well.
 * <p>New tests register it as a JUnit extension
 * ({@code @RegisterExtension static final RedisTestHarness HARNESS =
 * new RedisTestHarness;}) so the lifecycle glue itself is never re-typed —
 * connect/destroy callbacks fire around the whole class and callers read the
 * template via {@link #redis}.
 * <p>Posture unchanged: real Redis 6379 — lock/idempotent semantics are exactly
 * what mocking would prove nothing about; callers keep keys under a per-run
 * UUID prefix.
 */
public final class RedisTestHarness implements BeforeAllCallback, AfterAllCallback {

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;

    @Override
    public void beforeAll(ExtensionContext context) {
        connect();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        close();
    }

    /** Connect lazily to the local dev Redis; returns this harness for chaining. */
    public RedisTestHarness connect() {
        factory = new LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        return this;
    }

    public StringRedisTemplate redis() {
        return redis;
    }

    /** Release the connection factory (call from @AfterAll). */
    public void close() {
        if (factory != null) {
            factory.destroy();
        }
    }
}
