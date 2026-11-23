package com.sixbean.testpike.admin.session.api.dto;

import java.util.List;

/**
 * Register/renew input for one login state (admin contract face). Fields map
 * by name onto the core LoginStateInput plus projectId; the one-shot captured
 * {@code secret} is write-transient — core encrypts it into the vault and no
 * response ever carries it back (edit-with-blank secret keeps the stored
 * ciphertext, same semantics as the credential save request).
 */
public record LoginStateRegisterRequest(
        Long projectId,
        String stateName,
        String roleScope,
        String accountLabel,
        String kind,
        String credKey,
        String secret,
        String stateObjectKey,
        List<String> scopeOrigins,
        String remark,
        List<ExtraAuthRef> extraAuthRefs) {

    /**
 * Legacy ten-argument shape : callers that predate the
 * combined-session header list pass null, which core reads as "no list
 * supplied" — a re-registration then keeps any stored combined headers.
 * Only doors that pass an explicit list can rewrite them.
 */
    public LoginStateRegisterRequest(Long projectId, String stateName, String roleScope,
                                     String accountLabel, String kind, String credKey,
                                     String secret, String stateObjectKey,
                                     List<String> scopeOrigins, String remark) {
        this(projectId, stateName, roleScope, accountLabel, kind, credKey, secret,
                stateObjectKey, scopeOrigins, remark, null);
    }
}
