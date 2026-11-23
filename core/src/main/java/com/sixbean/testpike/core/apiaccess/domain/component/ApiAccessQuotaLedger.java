package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Per-caller call budget for the pass-through door.
 *
 * <p>A generative catalog means the model can address any read door, so the runaway shape to guard
 * is breadth, not one bad endpoint: a bounded number of pass-through reads per window per caller,
 * with the ceiling and the window both hot-updatable K8 keys (no threshold lives in code).
 *
 * <p>Fails closed: when the counter store itself is unreachable the door refuses rather than
 * running without a budget. The core service already needs Redis for confirm tokens and locks, so
 * a Redis failure is not a background condition this door can quietly tolerate.
 */
@Component
public class ApiAccessQuotaLedger {

    /** Ceiling of pass-through reads per window per caller (K8 hot-updatable). */
    public static final String KEY_MAX_CALLS = "ai.api-access.max-calls-per-window";

    /** Window length in seconds (K8 hot-updatable). */
    public static final String KEY_WINDOW_SECONDS = "ai.api-access.window-seconds";

    /** Fallback ceiling when the key is absent or unparsable. */
    static final long DEFAULT_MAX_CALLS = 12L;

    /** Fallback window when the key is absent or unparsable. */
    static final long DEFAULT_WINDOW_SECONDS = 600L;

    private static final Logger log = LoggerFactory.getLogger(ApiAccessQuotaLedger.class);

    /** Counter key namespace, package-visible so the same-package spec builds keys from this string. */
    static final String KEY_PREFIX = "tp:apiaccess:quota:";

    private final StringRedisTemplate redis;
    private final SystemConfigReader configReader;

    public ApiAccessQuotaLedger(StringRedisTemplate redis, SystemConfigReader configReader) {
        this.redis = redis;
        this.configReader = configReader;
    }

    /** Configured ceiling (read per call so a K8 change lands without a restart). */
    public long maxCalls() {
        return configReader.getPositiveLong(KEY_MAX_CALLS, DEFAULT_MAX_CALLS);
    }

    /**
     * Claim one slot of the budget for one caller.
     *
     * @param bucket caller-scoped bucket (operator id, or task id when the chain carries one)
     * @return null when claimed, otherwise the refusal text naming the ceiling and the switch
     */
    public String tryClaim(String bucket) {
        long limit = maxCalls();
        String key = KEY_PREFIX + (bucket == null || bucket.isBlank() ? "anonymous" : bucket.trim());
        try {
            Long used = redis.opsForValue().increment(key);
            long window = configReader.getPositiveLong(KEY_WINDOW_SECONDS, DEFAULT_WINDOW_SECONDS);
            if (used != null && used == 1L) {
                redis.expire(key, Duration.ofSeconds(window));
            }
            if (used != null && used > limit) {
                return "本轮接口直通调用已达上限 " + limit + " 次（窗口 " + window + " 秒），"
                        + "请基于已取到的结果作答；确需更多次可在系统配置页（/configs）调高参数 "
                        + KEY_MAX_CALLS;
            }
            return null;
        } catch (RuntimeException ex) {
            log.warn("pass-through quota store unreadable, refusing by design: {}", ex.getMessage());
            return "接口直通的用量账当前不可用（Redis 异常），本门按关闭处理；先恢复 Redis 再重试";
        }
    }

    /** Positive long from the K8 config face, falling back to the documented default. */
}
