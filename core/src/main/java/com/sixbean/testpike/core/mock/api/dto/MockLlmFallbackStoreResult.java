package com.sixbean.testpike.core.mock.api.dto;

/**
 * A-3 cache write outcome .
 * <p>{@code stored=false} is never silent: {@code reason} says in plain words
 * why nothing was written (budget overrun, blank body, space gone), mirroring
 * the read side's honest-miss posture. The cacheKey echoes the fingerprint so
 * the generation leg can pin its cassette against the exact row it produced.
 *
 * @param stored whether a cache row now stands behind this key
 * @param cacheKey fingerprint key written (or refused) — same scheme as reads
 * @param reason plain words when nothing was stored (null when stored)
 * @param expiresAt freshness horizon stamped on the row (null when unserved)
 */
public record MockLlmFallbackStoreResult(
        boolean stored,
        String cacheKey,
        String reason,
        java.time.LocalDateTime expiresAt) {

    /** Honest refusal — the only unserved shape allowed on the write side. */
    public static MockLlmFallbackStoreResult refused(String cacheKey, String reason) {
        return new MockLlmFallbackStoreResult(false, cacheKey, reason, null);
    }
}
