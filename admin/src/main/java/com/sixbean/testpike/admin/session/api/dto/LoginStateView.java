package com.sixbean.testpike.admin.session.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin mirror of the core ledger row (LoginStateInfo, same field names so the
 * RestClient bridge maps directly). Metadata only — the vault value never
 * appears here by construction: the ledger view has no secret component at all
 * (structural gate in LoginStateBridgeTest), consumption resolves server-side.
 */
public record LoginStateView(
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
 * Legacy seventeen-argument shape : readers that
 * predate the combined-session header list see an empty list — zero drift,
 * same convention as the core LoginStateInfo legacy constructor.
 */
    public LoginStateView(Long id, Long projectId, String stateName, String roleScope,
                          String accountLabel, String kind, Long credentialId, String credKey,
                          String stateObjectKey, List<String> scopeOrigins, Integer sourceKind,
                          LocalDateTime expiresAt, LocalDateTime lastVerifiedAt, Integer status,
                          String remark, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, projectId, stateName, roleScope, accountLabel, kind, credentialId, credKey,
                stateObjectKey, scopeOrigins, sourceKind, expiresAt, lastVerifiedAt, status,
                remark, createdAt, updatedAt, List.of());
    }
}
