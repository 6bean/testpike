package com.sixbean.testpike.core.cluster.api.dto;

import java.time.LocalDateTime;

/**
 * Pool-entry token issuance response: the plaintext token appears only once in this response and is never echoed after being carried by the generation command.
 * Only SHA-256 digests are stored in the database.
 */
public record EnrollTokenIssuedView(
        Long id,
        String token,
        String role,
        String projectScope,
        Integer maxUses,
        LocalDateTime expiresAt) {
}
