package com.sixbean.testpike.core.cluster.api.dto;

/**
 * Pool-entry token issuance input parameters (frozen document: one-time + TTL + machine fingerprint binding).
 * <p>maxUses null=1 (one machine one token by default); projectScope may be null (no project scope restriction);
 * actorRole=operator role (allow-roles whitelist consumes input parameters, cross-service via DTO field).
 * Pass-through must not be taken from request context; the issuance gate reads key installkit.allow-roles now.
 */
public record EnrollTokenIssueInput(
        String role,
        String projectScope,
        Integer maxUses,
        String remark,
        String actorRole) {
}
