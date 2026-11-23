package com.sixbean.testpike.core.mock.api.dto;

/**
 * Space save input (management-plane create/update payload; the production-env
 * ban and key-shape checks are enforced service-side, never here).
 *
 * @param name          display name
 * @param spaceKey      global routing handle for /mocks/{spaceKey}/**
 * @param alias         optional second path segment (null = none)
 * @param envId         bound environment profile (null = unbound; env_type=4 refused)
 * @param basePathMode  prefix | strict
 * @param authMode      none | token | cred
 * @param rateLimitRps  per-space cap (null = K8 default)
 * @param quotaDaily    per-space daily quota (null = K8 default)
 * @param priority      cross-space routing tie-breaker
 * @param chaosPercent  space-level fault dial 0-100 (null = 0, no injection)
 * @param missPolicy    passthrough | reject | llm-fallback (null = llm-fallback,
 *                      the derived ladder spaces had before the policy existed)
 */
public record MockSpaceInput(
        String name,
        String spaceKey,
        String alias,
        Long envId,
        String basePathMode,
        String authMode,
        Integer rateLimitRps,
        Long quotaDaily,
        Integer priority,
        Integer chaosPercent,
        String missPolicy) {

    /** Save payloads written before the miss policy existed (no policy choice). */
    public MockSpaceInput(String name, String spaceKey, String alias, Long envId,
                          String basePathMode, String authMode, Integer rateLimitRps,
                          Long quotaDaily, Integer priority, Integer chaosPercent) {
        this(name, spaceKey, alias, envId, basePathMode, authMode,
                rateLimitRps, quotaDaily, priority, chaosPercent, null);
    }
}
