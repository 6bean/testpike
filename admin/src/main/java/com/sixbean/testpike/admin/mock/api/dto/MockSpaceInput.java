package com.sixbean.testpike.admin.mock.api.dto;

/**
 * Space save input (admin bridge mirror of the core MockSpaceInput record —
 * field-for-field json mapping, zero reprocessing; the production-env ban and
 * key-shape checks are single-point guards inside core).
 *
 * @param name         display name
 * @param spaceKey     global routing handle for /mocks/{spaceKey}/**
 * @param alias        optional second path segment (null = none)
 * @param envId        bound environment profile (null = unbound; env_type=4 refused in core)
 * @param basePathMode prefix | strict
 * @param authMode     none | token | cred
 * @param rateLimitRps per-space cap (null = K8 default)
 * @param quotaDaily   per-space daily quota (null = K8 default)
 * @param priority      cross-space routing tie-breaker
 * @param chaosPercent  space-level fault dial (null = 0, no injection)
 * @param missPolicy    passthrough | reject | llm-fallback (null = llm-fallback,
 *                      the derived ladder; rung legality is core's single point)
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
}
