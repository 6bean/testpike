package com.sixbean.testpike.core.mock.api.dto;

import java.time.LocalDateTime;

/**
 * Space view returned by the internal endpoints and the snapshot payload.
 *
 * @param id row id
 * @param projectId owning project (H1 isolation anchor)
 * @param name display name
 * @param spaceKey global routing handle
 * @param alias optional second path segment
 * @param envId bound environment profile (null = unbound)
 * @param basePathMode prefix | strict
 * @param status 0 draft / 1 published / 2 retired
 * @param authMode none | token | cred
 * @param proxyTargetUrl bound recording/proxy target written by the start gate (null = never proxied)
 * @param rateLimitRps per-space rate cap (null = K8 default)
 * @param quotaDaily per-space daily quota (null = K8 default)
 * @param priority routing tie-breaker (lower wins)
 * @param chaosPercent space-level fault dial 0-100 (percent of served requests answered 503)
 * @param missPolicy what a no-stub-match request does: passthrough | reject | llm-fallback
 * @param expiresAt CI throwaway expiry instant (null = never expires; swept by the scheduled job)
 * @param version optimistic lock value
 * @param createdAt row creation time
 * @param updatedAt last mutation time
 * @param ruleCount stubs configured in the space, enabled and disabled both
 * (list projection only — single-row reads keep it null)
 * @param hitRate matched/total over the hit ledger, same spectrum as the
 * inspector count; null = no traffic sampled yet (an absent
 * sample is never dressed up as a 0% miss rate)
 */
public record MockSpaceInfo(
        Long id,
        Long projectId,
        String name,
        String spaceKey,
        String alias,
        Long envId,
        String basePathMode,
        Integer status,
        String authMode,
        String proxyTargetUrl,
        Integer rateLimitRps,
        Long quotaDaily,
        Integer priority,
        Integer chaosPercent,
        String missPolicy,
        LocalDateTime expiresAt,
        Integer version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer ruleCount,
        Double hitRate) {

    /** Row reads before the miss policy existed (the traffic plane defaults them). */
    public MockSpaceInfo(Long id, Long projectId, String name, String spaceKey,
                         String alias, Long envId, String basePathMode, Integer status,
                         String authMode, String proxyTargetUrl, Integer rateLimitRps,
                         Long quotaDaily, Integer priority, Integer chaosPercent,
                         LocalDateTime expiresAt, Integer version,
                         LocalDateTime createdAt, LocalDateTime updatedAt,
                         Integer ruleCount, Double hitRate) {
        this(id, projectId, name, spaceKey, alias, envId, basePathMode, status,
                authMode, proxyTargetUrl, rateLimitRps, quotaDaily, priority, chaosPercent,
                null, expiresAt, version, createdAt, updatedAt, ruleCount, hitRate);
    }
}
