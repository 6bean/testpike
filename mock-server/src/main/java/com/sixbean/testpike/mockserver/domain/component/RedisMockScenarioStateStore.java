package com.sixbean.testpike.mockserver.domain.component;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Redis-backed lane state store : the traffic plane's only
 * state store — scenario lane state never touches PostgreSQL. Logical keys
 * {@code space|scenario|scope|lane} become
 * {@code tp:mock:scen:{space}:{scenario}:{scope}:{lane}} so the operator can
 * inspect/clear lanes with plain redis-cli patterns.
 * <p>Exceptions propagate on purpose: the controller treats a read failure as
 * "state unknown" and fails closed on scenario traffic, honest-degrade
 * instead of a fabricated current state.
 */
public class RedisMockScenarioStateStore implements MockScenarioStateStore {

    /** Key root shared by every lane of every space. */
    private static final String KEY_ROOT = "tp:mock:scen:";

    private final StringRedisTemplate redis;

    public RedisMockScenarioStateStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public String read(String key) {
        return redis.opsForValue().get(transportKey(key));
    }

    @Override
    public void write(String key, String value, Duration ttl) {
        redis.opsForValue().set(transportKey(key), value, ttl);
    }

    /** Logical pipe-separated identity → colon-separated Redis key. */
    static String transportKey(String logicalKey) {
        return KEY_ROOT + logicalKey.replace('|', ':');
    }
}
