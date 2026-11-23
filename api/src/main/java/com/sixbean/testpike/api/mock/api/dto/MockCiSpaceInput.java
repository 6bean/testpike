package com.sixbean.testpike.api.mock.api.dto;

/**
 * Space shape accepted by the CI door (api mirror of core MockSpaceInput —
 * field-for-field json, zero reprocessing; every gate (key charset, env ban,
 * dial ranges) is the single-point save-chain validator inside core).
 * <p>CI pipelines only ever fill name + spaceKey; the remaining dials travel
 * as null so core applies its documented defaults (prefix mode, none auth,
 * priority 100, no chaos) rather than the door guessing values.
 *
 * @param name         display name (CI door defaults it to the routing key)
 * @param spaceKey     global routing handle for /mocks/{spaceKey}/**
 * @param alias        optional second path segment (null = none)
 * @param envId        bound environment profile (null = unbound)
 * @param basePathMode prefix | strict (null = core default)
 * @param authMode     none | token | cred (null = core default)
 * @param rateLimitRps per-space cap (null = K8 default)
 * @param quotaDaily   per-space daily quota (null = K8 default)
 * @param priority     cross-space routing tie-breaker (null = core default)
 * @param chaosPercent fault-injection share (null = none)
 */
public record MockCiSpaceInput(
        String name,
        String spaceKey,
        String alias,
        Long envId,
        String basePathMode,
        String authMode,
        Integer rateLimitRps,
        Long quotaDaily,
        Integer priority,
        Integer chaosPercent) {
}
