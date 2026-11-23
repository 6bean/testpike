package com.sixbean.testpike.core.session.api.dto;

import java.util.List;

/**
 * Capture/register input for one login state (AV2 ledger write shape).
 * {@code secret} carries the freshly captured value only at register time — the
 * service encrypts it into tp_credential (single vault) and stores nothing
 * secret on the ledger row itself. A blank secret re-points an existing credKey
 * without re-encrypting (renewal with unchanged material).
 */
public record LoginStateInput(
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
 * Legacy nine-argument shape : callers that predate the
 * combined-session header list pass null, which the ledger reads as
 * "no list supplied" — a re-capture of the same state then KEEPS any
 * combined headers the user registered, and an insert defaults to the
 * empty list. Only doors that pass an explicit list can rewrite it.
 */
    public LoginStateInput(String stateName, String roleScope, String accountLabel,
                           String kind, String credKey, String secret, String stateObjectKey,
                           List<String> scopeOrigins, String remark) {
        this(stateName, roleScope, accountLabel, kind, credKey, secret, stateObjectKey,
                scopeOrigins, remark, null);
    }
}
