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
        List<ExtraAuthRef> extraAuthRefs,
        Integer keepaliveEnabled,
        String keepaliveUrl,
        Integer keepaliveIntervalMinutes,
        LocalDateTime lastKeepaliveAt,
        String lastKeepaliveVerdict,
        Integer keepaliveFailStreak,
        String keepaliveStopReason) {

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
                remark, createdAt, updatedAt, List.of(),
                null, null, null, null, null, null, null);
    }

    /**
 * Eighteen-argument shape : readers that predate the keep-alive
 * display columns see the switch as off and the cadence as global.
 */
    public LoginStateInfo(Long id, Long projectId, String stateName, String roleScope,
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
