package com.sixbean.testpike.core.user.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;


@Component
public class PasswordAttemptGuard {

 
    static final int MAX_FAILURES = 5;

    
    static final int DEFAULT_WINDOW_MINUTES = 15;

 
    public static final String WINDOW_KEY = "auth.pwd-attempt-window-minutes";

    
    static final String BLOCKED_MSG_FMT = "原密码错误次数过多，请 %d 分钟后再试";

    
    static final String KEY_PREFIX = "auth:pwd-attempt:";

    private static final Logger log = LoggerFactory.getLogger(PasswordAttemptGuard.class);

    private final StringRedisTemplate redis;
    private final com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder configCacheHolder;

    
    private static final DefaultRedisScript<Long> RECORD_SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]) "
                    + "if count == 1 or redis.call('PTTL', KEYS[1]) < 0 then "
                    + "  redis.call('PEXPIRE', KEYS[1], ARGV[1]) "
                    + "end "
                    + "return count", Long.class);

    public PasswordAttemptGuard(StringRedisTemplate redis,
                                com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder configCacheHolder) {
        this.redis = redis;
        this.configCacheHolder = configCacheHolder;
    }

    
    int windowMinutes() {
        String raw = configCacheHolder.get(WINDOW_KEY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_WINDOW_MINUTES;
        }
        try {
            int minutes = Integer.parseInt(raw.trim());
            return minutes > 0 ? minutes : DEFAULT_WINDOW_MINUTES;
        } catch (NumberFormatException e) {
            return DEFAULT_WINDOW_MINUTES;
        }
    }

    
    public void check(long userId) {
        String count;
        try {
            count = redis.opsForValue().get(KEY_PREFIX + userId);
        } catch (RuntimeException e) {
            log.warn("改密限流计数读取失败，降级放行 userId={}: {}", userId, e.getMessage());
            return;
        }
        if (count != null && parseCount(count) >= MAX_FAILURES) {
            throw new ServiceException(String.format(BLOCKED_MSG_FMT, windowMinutes()),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    public void recordFailure(long userId) {
        try {
            redis.execute(RECORD_SCRIPT, Collections.singletonList(KEY_PREFIX + userId),
                    String.valueOf(windowMinutes() * 60_000L));
        } catch (RuntimeException e) {
            log.warn("改密限流计数写入失败（本回合计数丢失，不阻断改密）userId={}: {}", userId, e.getMessage());
        }
    }

    
    public void clear(long userId) {
        try {
            redis.delete(KEY_PREFIX + userId);
        } catch (RuntimeException e) {
            log.warn("改密限流计数清零失败 userId={}: {}", userId, e.getMessage());
        }
    }

    private static long parseCount(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return MAX_FAILURES;
        }
    }
}
