package com.sixbean.testpike.core.mcp.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.config.domain.component.TtlSecondsPolicy;
import com.sixbean.testpike.core.mcp.api.dto.McpConfirmGrantTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/**
 * One-shot confirmation reference store for the MCP in-session confirmation loop
 * (tool batch scope correction of 2026-11-16: outside agents accept or refuse a
 * drafted write from inside their own conversation, never by signing into testPike).
 * <p>Two-state contract mirroring the internal write gate (ToolConfirmTokenStore
 * precedent, same Redis externalized-state discipline): {@link #issue} binds a random
 * reference to the full drafted envelope in Redis under {@code mcp:confirm:{ref}} with
 * an automatic TTL end-of-life (no sweep job, R57 lifecycle bound at the creation
 * point); {@link #take} atomically consumes it (GETDEL — replay and the UI-channel
 * race both lose on the second presentation, first-wins mutual exclusion).
 * <p>Fail-closed like the ticket store: a Redis write or read failure refuses loudly.
 * An untrusted grant state must never silently let a write through.
 * <p>TTL = hot key {@code mcp.confirm.ttl-seconds} (migration seed 1800, read live on
 * every issuance; illegal or non-positive values fall back to the shipped default).
 */
@Component
public class McpConfirmGrantStore {

    /** Pending-grant key prefix (observable ops namespace, same discipline as the ticket store). */
    static final String KEY_PREFIX = "mcp:confirm:";

    /** TTL hot key (tp_sys_config, seeded by the matching migration). */
    public static final String TTL_KEY = "mcp.confirm.ttl-seconds";

    /** Shipped default validity seconds; kept in sync with the migration seed (drift = defect). */
    public static final long DEFAULT_TTL_SECONDS = 1800L;

    private static final Logger log = LoggerFactory.getLogger(McpConfirmGrantStore.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final ConfigCacheHolder configCacheHolder;
    private final ObjectMapper objectMapper;

    public McpConfirmGrantStore(StringRedisTemplate redis,
                                ConfigCacheHolder configCacheHolder,
                                ObjectMapper objectMapper) {
        this.redis = redis;
        this.configCacheHolder = configCacheHolder;
        this.objectMapper = objectMapper;
    }

    /**
     * Issue a one-shot reference bound to the drafted envelope.
     *
     * @return reference, digest and the validity window in effect at issuance
     */
    public McpConfirmGrantTicket issue(McpConfirmEnvelope envelope) {
        long ttl = ttlSeconds();
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        String ref = HexFormat.of().formatHex(buf);
        try {
            redis.opsForValue().set(KEY_PREFIX + ref, objectMapper.writeValueAsString(envelope),
                    Duration.ofSeconds(ttl));
        } catch (Exception e) {
            log.warn("MCP confirm grant could not be stored: {}", e.getMessage());
            throw new ServiceException("写确认服务暂不可用（确认码无法保存，缓存服务未响应），未产生任何变更：请过一分钟后重新发起写操作获取确认码；连续多次失败请联系系统管理员在「系统设置」页排查缓存服务配置",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return new McpConfirmGrantTicket(ref, envelope.summary(), ttl);
    }

    /**
     * Atomically consume a reference (GETDEL semantics): the first presentation yields
     * the envelope, every later one (replay, forged, expired, other channel first)
     * yields {@code null} and the caller refuses in plain words.
     */
    public McpConfirmEnvelope take(String confirmRef) {
        String raw;
        try {
            raw = redis.opsForValue().getAndDelete(KEY_PREFIX + confirmRef);
        } catch (RuntimeException e) {
            log.warn("MCP confirm grant state unreadable (Redis unavailable): {}", e.getMessage());
            throw new ServiceException("写确认服务暂不可用（确认码无法校验，缓存服务未响应），本次确认未生效：请重新发起写操作获取新确认码后再确认；连续多次失败请联系系统管理员在「系统设置」页排查缓存服务配置",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, McpConfirmEnvelope.class);
        } catch (Exception e) {
            // A corrupt value is already removed; refusing loudly is fail-closed.
            log.warn("MCP confirm grant value is corrupt: {}", e.getMessage());
            throw new ServiceException("确认码状态异常（无法解析），本次确认未生效：请重新发起写操作获取新的 confirm_ref，或在「AI 对话」面板的草案卡中确认",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** Effective validity seconds read live per issuance (hot-updatable, no caching). */
    long ttlSeconds() {
        return resolveTtlSeconds(configCacheHolder.get(TTL_KEY));
    }

    /** Pure TTL resolution: null/blank/illegal/non-positive fall back to the shipped default. */
    public static long resolveTtlSeconds(String raw) {
        return TtlSecondsPolicy.resolve(raw, DEFAULT_TTL_SECONDS);
    }
}
