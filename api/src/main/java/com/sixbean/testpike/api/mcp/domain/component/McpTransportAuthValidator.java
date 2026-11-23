package com.sixbean.testpike.api.mcp.domain.component;

import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;


public class McpTransportAuthValidator implements ServerTransportSecurityValidator {

    private static final String AUTHORIZATION = "Authorization";
    private static final long CACHE_TTL_MILLIS = 30_000L;
    private static final int CACHE_SWEEP_THRESHOLD = 200;

    private final McpBearerAuthenticator authenticator;
    private final Function<String, McpRateLimitDecision> rateLimiter;
    private final Function<String, McpRateLimitDecision> dailyQuota;
    private final ConcurrentHashMap<String, CachedAuth> pending = new ConcurrentHashMap<>();

    
    public McpTransportAuthValidator(McpBearerAuthenticator authenticator,
                                     Function<String, McpRateLimitDecision> rateLimiter) {
        this(authenticator, rateLimiter, key -> McpRateLimitDecision.allow());
    }

    /**
     * Full constructor adding the per-token daily quota gate.
     *
     * @param dailyQuota quota function (real wiring = McpDailyQuotaGuard::tryAcquire,
     *                   keyed by the same token fingerprint; consulted only after a
     *                   successful authentication and a passing rate-limit decision)
     */
    public McpTransportAuthValidator(McpBearerAuthenticator authenticator,
                                     Function<String, McpRateLimitDecision> rateLimiter,
                                     Function<String, McpRateLimitDecision> dailyQuota) {
        this.authenticator = authenticator;
        this.rateLimiter = rateLimiter;
        this.dailyQuota = dailyQuota;
    }

    @Override
    public void validateHeaders(Map<String, List<String>> headers)
            throws ServerTransportSecurityException {
        String authorization = firstHeader(headers, AUTHORIZATION);
        McpAuthResult result = authenticator.authenticate(authorization);
        if (!result.authenticated()) {
            throw new ServerTransportSecurityException(401, result.message());
        }
        McpRateLimitDecision decision = rateLimiter.apply(sha256Hex(authorization));
        if (!decision.allowed()) {
            throw new ServerTransportSecurityException(429, decision.message());
        }
        // Daily quota gate runs right after the rate-limit decision and only for
        // authenticated callers, so denials never consume the day budget either way.
        McpRateLimitDecision quota = dailyQuota.apply(sha256Hex(authorization));
        if (!quota.allowed()) {
            throw new ServerTransportSecurityException(429, quota.message());
        }
        sweepIfNeeded();
        pending.put(authorization, new CachedAuth(result.principal(), System.currentTimeMillis()));
    }

    
    public Optional<McpPrincipal> cachedPrincipal(String authorization) {
        if (authorization == null) {
            return Optional.empty();
        }
        CachedAuth cached = pending.remove(authorization);
        if (cached == null || System.currentTimeMillis() - cached.atMillis() > CACHE_TTL_MILLIS) {
            return Optional.empty();
        }
        return Optional.of(cached.principal());
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        if (values == null || values.isEmpty()) {
            values = headers.get(name.toLowerCase());
        }
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String sha256Hex(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用（JVM 规范算法，理论不可达）", ex);
        }
    }

    
    private void sweepIfNeeded() {
        if (pending.size() <= CACHE_SWEEP_THRESHOLD) {
            return;
        }
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> now - e.getValue().atMillis() > CACHE_TTL_MILLIS);
    }
}
