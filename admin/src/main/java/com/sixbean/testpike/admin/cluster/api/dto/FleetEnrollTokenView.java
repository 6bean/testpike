package com.sixbean.testpike.admin.cluster.api.dto;

import java.time.LocalDateTime;

/**
 * Admin mirror of the core enrollment-token ledger row (structurally plaintext-free:
 * the core view already excludes the digest column and this bridge adds no token field,
 * so a list call can never carry a usable secret out of the fleet page).
 */
public record FleetEnrollTokenView(
        Long id,
        String role,
        String projectScope,
        Integer maxUses,
        Integer usedCount,
        Integer status,
        String boundFingerprint,
        LocalDateTime expiresAt,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
