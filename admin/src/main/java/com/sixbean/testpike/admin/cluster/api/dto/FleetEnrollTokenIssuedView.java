package com.sixbean.testpike.admin.cluster.api.dto;

import java.time.LocalDateTime;

/**
 * Issuance answer of the enroll-token bridge. The token field is the one-time plaintext
 * that core returns exactly in this response (digest-only storage on the core side);
 * nothing here is re-derived and the ledger view above never repeats it.
 */
public record FleetEnrollTokenIssuedView(
        Long id,
        String token,
        String role,
        String projectScope,
        Integer maxUses,
        LocalDateTime expiresAt) {
}
