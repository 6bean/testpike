package com.sixbean.testpike.admin.mock.api.dto;

import java.time.LocalDateTime;

/**
 * Space view (admin bridge; field-for-field mirror of the core MockSpaceInfo
 * record — nulls stay null so the UI renders honest empties: alias absent,
 * env unbound, limits following K8 defaults rather than bridge-filled numbers).
 *
 * @param id           row id
 * @param projectId    owning project (H1 isolation anchor)
 * @param name         display name
 * @param spaceKey     global routing handle
 * @param alias        optional second path segment (null = none)
 * @param envId        bound environment profile (null = unbound)
 * @param basePathMode prefix | strict
 * @param status       0 draft / 1 published / 2 retired
 * @param authMode     none | token | cred
 * @param proxyTargetUrl bound recording/proxy target (null = never proxied)
 * @param rateLimitRps per-space rate cap (null = K8 default)
 * @param quotaDaily   per-space daily quota (null = K8 default)
 * @param priority     routing tie-breaker (lower wins)
 * @param chaosPercent space-level fault dial 0-100 (percent of served requests answered 503)
 * @param missPolicy   what a no-stub-match request does: passthrough | reject | llm-fallback
 * @param expiresAt    CI throwaway expiry instant (null = never expires)
 * @param version      optimistic lock value
 * @param createdAt    row creation time
 * @param updatedAt    last mutation time
 * @param ruleCount    stubs configured in the space (list projection; null on
 *                     single-row reads — the bridge never invents numbers)
 * @param hitRate      matched/total over the hit ledger (core-spectrum
 *                     computed; null = no traffic sampled yet)
 */
public record MockSpaceView(
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
}
