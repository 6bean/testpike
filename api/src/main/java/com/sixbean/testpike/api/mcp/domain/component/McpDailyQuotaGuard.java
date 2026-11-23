package com.sixbean.testpike.api.mcp.domain.component;

import com.sixbean.testpike.common.ratelimit.FixedWindowRedisCounter;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.IntSupplier;

/**
 * Per-token daily call-quota guard for the MCP endpoint (governance key
 * mcp.quota.calls-per-token-day, factory default 2000).
 * <p>Complements {@link McpRateLimiter}: same fixed-window counter primitive
 * (common {@link FixedWindowRedisCounter}, reuse instead of a second counting
 * implementation) but windowed by calendar day, so an API token cannot exceed
 * its agreed daily budget even when spread below the per-minute limit.
 * <p>Redis key = {@code KEY_PREFIX + tokenFingerprint + ":" + yyyy-MM-dd}. The
 * day boundary lives in the key itself and the TTL (seconds until local
 * midnight, set on the first hit of that key) is cleanup-only: expired day
 * keys never accumulate (resource pairing = terminal delete via TTL).
 * <p>The cap is read live through the injected IntSupplier on every call
 * (hot-updatable governance value; no startup snapshot, no hardcoded limit).
 * Call order on the transport surface = authenticate -> rate limit -> daily
 * quota, so denied/unauthenticated requests never consume quota (same counting
 * discipline as the rate limiter).
 */
public class McpDailyQuotaGuard {

    /** Redis key prefix (single source, shared with tests for assertions and cleanup). */
    public static final String KEY_PREFIX = "mcp:quota:day:";

    /** Governance key name; also quoted in the denial message so operators can find it. */
    public static final String QUOTA_KEY = "mcp.quota.calls-per-token-day";

    /** Fallback cap when the governance key is unreadable (kept equal to the seed default). */
    public static final int DEFAULT_LIMIT = 2000;

    private final FixedWindowRedisCounter counter;
    private final IntSupplier limitSupplier;
    private final Clock clock;

    public McpDailyQuotaGuard(StringRedisTemplate redis, IntSupplier limitSupplier) {
        this(redis, limitSupplier, Clock.systemDefaultZone());
    }

    McpDailyQuotaGuard(StringRedisTemplate redis, IntSupplier limitSupplier, Clock clock) {
        this.counter = new FixedWindowRedisCounter(redis);
        this.limitSupplier = limitSupplier;
        this.clock = clock;
    }

    /**
     * Counts one authenticated request for the token and returns the quota decision.
     *
     * @param tokenKey token fingerprint (SHA-256 hex, never the plaintext token)
     */
    public McpRateLimitDecision tryAcquire(String tokenKey) {
        String dayKey = dayKey(tokenKey);
        long used = counter.hit(dayKey, Duration.ofSeconds(secondsUntilMidnight()));
        int limit = Math.max(1, limitSupplier.getAsInt());
        if (used <= limit) {
            return McpRateLimitDecision.allow();
        }
        int resetIn = Math.max(1, counter.remainingSeconds(dayKey));
        return McpRateLimitDecision.deny(
                "MCP 今日调用配额已达上限（上限 " + limit + " 次，当前已发起 " + used
                        + " 次），自然日零点自动重置（约 " + Math.max(1, resetIn / 60) + " 分钟后重置）；"
                        + "如需更高额度请在系统设置调整 " + QUOTA_KEY,
                resetIn);
    }

    /** Redis key for the current calendar day window (shared with tests via prefix + date). */
    public String dayKey(String tokenKey) {
        return KEY_PREFIX + tokenKey + ":" + LocalDate.now(clock);
    }

    private long secondsUntilMidnight() {
        LocalDateTime now = LocalDateTime.now(clock);
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).getSeconds();
    }
}
