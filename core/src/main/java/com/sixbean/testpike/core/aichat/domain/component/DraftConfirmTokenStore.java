package com.sixbean.testpike.core.aichat.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.config.domain.component.TtlSecondsPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Draft confirmation ticket store (AO batch stage 1; generalized from
 * the repair-only store of stage 3b the key prefix and TTL
 * semantics are deliberately unchanged so in-flight tickets survive the
 * rewire; AM batch piece 2 added the GROUP key space, one ticket bound
 * to a whole answer instead of a single card).
 * <p>Same two-state posture as the write-tool door ({@code tool:confirm:} in
 * the tool domain): {@link #issue} parks a random one-shot key bound to the
 * draft id; {@link #consume} removes it atomically with GETDEL, so replay,
 * expiry, forgery and cross-draft reuse all fail the same human-readable way.
 * The group entry points reuse the exact same machinery with the message id
 * as the bound value — one door, one ticket discipline (red line 6).
 * <p>Kept as its own component rather than reusing the tool store: the bound
 * id lives in a different namespace (draft vs tool) and the dialog door owns
 * its key prefix — sharing would weld the aichat lane to the tool domain for
 * no behavioural gain.
 * <p><b>Fail-closed direction matches the tool door, opposite to rate
 * limiters</b>: if Redis is unreachable, issuing refuses (no ticket = no door,
 * silently allowing the write would make the confirmation gate decorative)
 * and consuming refuses too (ticket state untrusted = refuse).
 * <p>TTL reuses the hot-reload key {@code ai.tool.confirm-ttl-seconds} — the
 * write-confirmation window is one platform-wide dial, not per-door copies.
 */
@Component
public class DraftConfirmTokenStore {

    /** Ticket key prefix (ops-observable key space, same discipline as the lock family). */
    static final String KEY_PREFIX = "aichat:draft:confirm:";

    /** Group-ticket key space: one ticket bound to a whole answer . */
    static final String GROUP_KEY_PREFIX = "aichat:draft:group-confirm:";

    /** TTL hot-reload key shared with the tool confirmation door (single dial). */
    public static final String TTL_KEY = "ai.tool.confirm-ttl-seconds";

    /** Fallback TTL when the key is missing or garbage. */
    static final long DEFAULT_TTL_SECONDS = 300L;

    private static final Logger log = LoggerFactory.getLogger(DraftConfirmTokenStore.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final ConfigCacheHolder configCacheHolder;

    public DraftConfirmTokenStore(StringRedisTemplate redis,
                                  ConfigCacheHolder configCacheHolder) {
        this.redis = redis;
        this.configCacheHolder = configCacheHolder;
    }

    /**
     * Issue a ticket bound to one draft id — consuming side re-checks the
     * binding, so a ticket minted for draft A cannot open draft B.
     */
    public String issue(long draftId) {
        return issueInto(KEY_PREFIX, String.valueOf(draftId));
    }

    /**
     * Issue a ticket bound to one group (group door): the bound value is the
     * DraftGroupScope wire, so a ticket minted for one group can never open
     * another group nor the single-card door (different key spaces,
     * {@link #consume} vs {@link #consumeGroup}).
     */
    public String issueGroup(String groupScope) {
        return issueInto(GROUP_KEY_PREFIX, groupScope);
    }

    /** Live TTL echoed to the dock for its countdown display. */
    public long ttlSeconds() {
        return ttlSeconds(configCacheHolder.get(TTL_KEY));
    }

    /**
     * Pure TTL resolution (missing/blank/garbage/non-positive falls back to the
     * default; positive values above the floor are honoured as typed). Exposed
     * for the range-guard contract test without a config row.
     */
    public static long ttlSeconds(String raw) {
        return TtlSecondsPolicy.resolve(raw, DEFAULT_TTL_SECONDS);
    }

    /**
     * Atomically consume the ticket for the expected draft (one-shot GETDEL).
     * Missing/expired/forged/replayed token, or one bound to a different
     * draft → human-readable refusal and zero writes on the caller side.
     */
    public void consume(String token, long draftId) {
        consumeFrom(KEY_PREFIX, token, String.valueOf(draftId));
    }

    /** Group counterpart of {@link #consume}: the ticket must be bound to this group scope. */
    public void consumeGroup(String token, String groupScope) {
        consumeFrom(GROUP_KEY_PREFIX, token, groupScope);
    }

    /** One issuance machine for both key spaces — bound value is the id being guarded. */
    private String issueInto(String keySpace, String boundId) {
        long ttl = ttlSeconds();
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        String token = HexFormat.of().formatHex(buf);
        try {
            redis.opsForValue().set(keySpace + token, String.valueOf(boundId),
                    Duration.ofSeconds(ttl));
        } catch (RuntimeException e) {
            log.warn("draft ticket issue failed (Redis unavailable) boundId={}: {}",
                    boundId, e.getMessage());
            throw new ServiceException("AI 草案确认票暂时无法保存（缓存服务不可用）；请稍后在「AI 对话」重新发起这条草案的确认，持续失败时请联系管理员检查 Redis 缓存服务",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return token;
    }

    /** One consume machine for both key spaces (GETDEL, refusal wording shared). */
    private void consumeFrom(String keySpace, String token, String boundId) {
        String bound;
        try {
            bound = redis.opsForValue().getAndDelete(keySpace + token);
        } catch (RuntimeException e) {
            log.warn("draft ticket consume failed (Redis unavailable) boundId={}: {}",
                    boundId, e.getMessage());
            throw new ServiceException("AI 草案确认票暂时无法校验（缓存服务不可用）；请稍后在「AI 对话」重新发起这条草案的确认，持续失败时请联系管理员检查 Redis 缓存服务",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (bound == null) {
            throw new ServiceException("AI 草案确认票无效或已过期——草案需在「AI 对话」的草案卡上确认后才会写入，"
                    + "请在「AI 对话」重新打开该草案的「草案确认卡」点「接受」获取新确认票", BizErrorCode.VALIDATION_ERROR);
        }
        if (!boundId.equals(bound)) {
            throw new ServiceException("AI 草案确认票与本案草案不匹配，已拒绝写入——请回到「AI 对话」打开该草案的「草案确认卡」重新确认，旧确认票不会写入",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}
