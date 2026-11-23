package com.sixbean.testpike.core.cluster.api.dto;

import java.time.LocalDateTime;

/**
 * Token pool management view (list/detail; structurally excludes token/digest fields = zero plaintext exposure gate).
 */
public record EnrollTokenView(
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
