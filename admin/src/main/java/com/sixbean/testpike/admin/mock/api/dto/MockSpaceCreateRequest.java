package com.sixbean.testpike.admin.mock.api.dto;

/**
 * Space create request (admin face): the owning project travels inside the
 * body so the frontend posts one JSON, and the bridge splits projectId onto the
 * core query parameter with the remaining fields as the save input.
 *
 * @param projectId    owning project (H1 isolation anchor, checked in core)
 * @param name         display name
 * @param spaceKey     global routing handle
 * @param alias        optional second path segment
 * @param envId        bound environment profile (null = unbound)
 * @param basePathMode prefix | strict
 * @param authMode     none | token | cred
 * @param rateLimitRps per-space cap (null = K8 default)
 * @param quotaDaily   per-space daily quota (null = K8 default)
 * @param priority     routing tie-breaker
 * @param chaosPercent space-level fault dial 0-100 (null = 0, no injection)
 * @param missPolicy   passthrough | reject | llm-fallback (null = llm-fallback;
 *                     rung legality is decided in the core save chain)
 */
public record MockSpaceCreateRequest(
        Long projectId,
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

    /** Rule payload minus the owner id (shape core accepts as save input). */
    public MockSpaceInput toInput() {
        return new MockSpaceInput(name, spaceKey, alias, envId,
                basePathMode, authMode, rateLimitRps, quotaDaily, priority,
                chaosPercent, missPolicy);
    }
}
