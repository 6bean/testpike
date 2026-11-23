package com.sixbean.testpike.aiservice.mock.infrastructure.remote;

/**
 * A-3 cache write verdict relayed from the core store door ;
 * remote brief pattern of the mock drafting family). {@code stored=false}
 * always carries {@code reason} in plain words — the generation leg relays
 * that verdict to its caller instead of inventing its own bookkeeping story.
 *
 * @param stored whether core cased the artifact into the cache
 * @param cacheKey fingerprint key written or refused (same scheme as reads)
 * @param reason plain words when nothing was stored (null when stored)
 */
public record MockLlmFallbackStoreBrief(
        boolean stored,
        String cacheKey,
        String reason) {
}
