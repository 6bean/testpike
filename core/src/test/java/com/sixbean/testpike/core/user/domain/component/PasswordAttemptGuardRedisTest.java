package com.sixbean.testpike.core.user.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class PasswordAttemptGuardRedisTest {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder configCacheHolder;

    private PasswordAttemptGuard guardA;
    private PasswordAttemptGuard guardB;

    private static final long USER_ID = 900001L;
    private static final String KEY = "auth:pwd-attempt:" + USER_ID;

    @BeforeEach
    void cleanKey() {
        redis.delete(KEY);
        guardA = new PasswordAttemptGuard(redis, configCacheHolder);
        guardB = new PasswordAttemptGuard(redis, configCacheHolder);
    }

    @AfterEach
    void dropKey() {
        redis.delete(KEY);
    }

    
    @Test
    void 双实例共享计数_A记录失败达阈值_B拦截() {
        for (int i = 0; i < PasswordAttemptGuard.MAX_FAILURES; i++) {
            guardA.recordFailure(USER_ID);
        }
        ServiceException blocked = assertThrows(ServiceException.class,
                () -> guardB.check(USER_ID), "B 实例必须看到 A 实例记录的失败计数（Redis 共享）");
        assertTrue(blocked.getMessage().contains("次数过多"), "限流人话: " + blocked.getMessage());
    }

    
    @Test
    void 未达阈值放行_A清零后_B放行() {
        for (int i = 0; i < PasswordAttemptGuard.MAX_FAILURES - 1; i++) {
            guardA.recordFailure(USER_ID);
        }
        assertDoesNotThrow(() -> guardB.check(USER_ID), "4 次失败未达阈值不拦截");
        guardA.recordFailure(USER_ID);
        assertThrows(ServiceException.class, () -> guardB.check(USER_ID), "第 5 次后拦截");
        guardA.clear(USER_ID);
        assertDoesNotThrow(() -> guardB.check(USER_ID), "清零后（成功改密语义）放行");
        assertTrue(Boolean.FALSE.equals(redis.hasKey(KEY)), "clear 必须删共享键（非仅本地 Map）");
    }

    
    @Test
    void 失败计数带原生TTL窗口() {
        guardA.recordFailure(USER_ID);
        Long ttlMillis = redis.getExpire(KEY, TimeUnit.MILLISECONDS);
        assertNotNull(ttlMillis, "计数键必须有 TTL（窗口过期自动重置的依据）");
        assertTrue(ttlMillis > 0 && ttlMillis <= PasswordAttemptGuard.DEFAULT_WINDOW_MINUTES * 60_000L,
                "TTL 落在 (0, 15min] 窗口内，实测: " + ttlMillis);
    }

    
    @Test
    void 窗口内累加_窗口起点不漂移() {
        guardA.recordFailure(USER_ID);
        Long ttlFirst = redis.getExpire(KEY, TimeUnit.MILLISECONDS);
        assertNotNull(ttlFirst);
        guardA.recordFailure(USER_ID);
        Long ttlSecond = redis.getExpire(KEY, TimeUnit.MILLISECONDS);
        assertNotNull(ttlSecond);
        assertTrue(ttlSecond <= ttlFirst,
                "二次失败不得重置窗口（" + ttlFirst + " → " + ttlSecond + " 允许毫秒级自然衰减）");
        assertEquals("2", redis.opsForValue().get(KEY), "计数累加可见");
    }
}
