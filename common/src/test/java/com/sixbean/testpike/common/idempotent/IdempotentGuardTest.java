package com.sixbean.testpike.common.idempotent;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class IdempotentGuardTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);

    private final IdempotentGuard guard = new IdempotentGuard(redis);

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    private String key(String name) {
        return "guard:" + RUN_ID + ":" + name;
    }

    @Test
    void 同key重复执行_仅执行一次_结果一致() {
        String k = key("replay");
        AtomicInteger calls = new AtomicInteger();
        String first = guard.execute(k, () -> {
            calls.incrementAndGet();
            return "result-1";
        });
        String second = guard.execute(k, () -> {
            calls.incrementAndGet();
            return "result-2";
        });

        assertEquals("result-1", first);
        assertEquals("result-1", second, "重复调用返回首次结果");
        assertEquals(1, calls.get(), "supplier 只执行一次");
    }

    @Test
    void 不同key_各自执行() {
        AtomicInteger calls = new AtomicInteger();
        guard.execute(key("a"), calls::incrementAndGet);
        guard.execute(key("b"), calls::incrementAndGet);
        assertEquals(2, calls.get(), "不同 key 互不影响");
    }

    
    @Test
    void 双实例同键_共享幂等记录_回放首次结果() {
        IdempotentGuard guardB = new IdempotentGuard(redis);
        String k = key("cross-instance");
        AtomicInteger calls = new AtomicInteger();
        guard.execute(k, () -> {
            calls.incrementAndGet();
            return "first";
        });
        String replay = guardB.execute(k, () -> {
            calls.incrementAndGet();
            return "second";
        });
        assertEquals("first", replay, "B 实例必须回放 A 实例的首次结果（Redis 共享）");
        assertEquals(1, calls.get(), "跨实例同键只执行一次");
    }

    @Test
    void supplier抛异常_不缓存_下次重试成功() {
        String k = key("fail");
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> guard.execute(k, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("首次失败");
        }));
        String ok = guard.execute(k, () -> {
            calls.incrementAndGet();
            return "重试成功";
        });
        assertEquals("重试成功", ok, "失败不缓存，可重试");
        assertEquals(2, calls.get());
    }

    
    @Test
    void 结构化返回值_跨实例回放类型一致() {
        IdempotentGuard guardB = new IdempotentGuard(redis);
        String k = key("map");
        Map<String, Long> first = guard.execute(k, () -> Map.of("taskId", 42L));
        Map<String, Long> replay = guardB.execute(k, () -> Map.of("taskId", -1L));
        assertEquals(42L, replay.get("taskId"), "回放值=首次值（不二次执行）");
        assertEquals(first, replay);
    }

 
    @Test
    void 在途二发_40903拒绝不执行() {
        String k = key("inflight");
        guard.execute(k, () -> "done");
        redis.opsForValue().set("idempotent:" + k, "E:manual-token", 60, TimeUnit.SECONDS);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.execute(k, () -> "should-not-run"));
        assertEquals(40903, ex.getCode());
        assertTrue(ex.getMessage().contains("处理中"), "人话: " + ex.getMessage());
    }

    
    @Test
    void 记录带24h原生TTL() {
        String k = key("ttl");
        guard.execute(k, () -> "v");
        Long ttlSeconds = redis.getExpire("idempotent:" + k, TimeUnit.SECONDS);
        assertInstanceOf(Long.class, ttlSeconds);
        assertTrue(ttlSeconds > 23 * 3600 && ttlSeconds <= 24 * 3600,
                "TTL 落 (23h, 24h]，实测: " + ttlSeconds);
    }
}
