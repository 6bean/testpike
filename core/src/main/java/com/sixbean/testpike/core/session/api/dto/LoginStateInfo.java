package com.sixbean.testpike.core.session.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Ledger view row for one login state. Carries metadata only — the vault value
 * never appears here (no plaintext, no cipher, no mask of it); consumption
 * resolves through SessionStateSupport on the server side.
 */
public record LoginStateInfo(
        Long id,
        Long projectId,
        String stateName,
        String roleScope,
        String accountLabel,
        String kind,
        Long credentialId,
        String credKey,
        String stateObjectKey,
        List<String> scopeOrigins,
        Integer sourceKind,
        LocalDateTime expiresAt,
        LocalDateTime lastVerifiedAt,
        Integer status,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<ExtraAuthRef> extraAuthRefs) {

    /**
 * Legacy seventeen-argument shape : readers that predate the
 * combined-session header list see an empty list — zero drift.
 */
    public LoginStateInfo(Long id, Long projectId, String stateName, String roleScope,
                          String accountLabel, String kind, Long credentialId, String credKey,
                          String stateObjectKey, List<String> scopeOrigins, Integer sourceKind,
                          LocalDateTime expiresAt, LocalDateTime lastVerifiedAt, Integer status,
                          String remark, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, projectId, stateName, roleScope, accountLabel, kind, credentialId, credKey,
                stateObjectKey, scopeOrigins, sourceKind, expiresAt, lastVerifiedAt, status,
                remark, createdAt, updatedAt, List.of());
    }
}
