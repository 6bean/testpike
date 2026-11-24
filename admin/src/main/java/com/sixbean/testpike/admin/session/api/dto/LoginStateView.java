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
        List<ExtraAuthRef> extraAuthRefs,
        Integer keepaliveEnabled,
        String keepaliveUrl,
        Integer keepaliveIntervalMinutes,
        LocalDateTime lastKeepaliveAt,
        String lastKeepaliveVerdict,
        Integer keepaliveFailStreak,
        String keepaliveStopReason) {

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
                remark, createdAt, updatedAt, List.of(),
                null, null, null, null, null, null, null);
    }

    /**
 * Eighteen-argument shape : readers that predate the
 * keep-alive display columns see the row as never enrolled.
 */
    public LoginStateView(Long id, Long projectId, String stateName, String roleScope,
                          String accountLabel, String kind, Long credentialId, String credKey,
                          String stateObjectKey, List<String> scopeOrigins, Integer sourceKind,
                          LocalDateTime expiresAt, LocalDateTime lastVerifiedAt, Integer status,
                          String remark, LocalDateTime createdAt, LocalDateTime updatedAt,
                          List<ExtraAuthRef> extraAuthRefs) {
        this(id, projectId, stateName, roleScope, accountLabel, kind, credentialId, credKey,
                stateObjectKey, scopeOrigins, sourceKind, expiresAt, lastVerifiedAt, status,
                remark, createdAt, updatedAt, extraAuthRefs,
                0, null, null, null, null, 0, null);
    }
}
