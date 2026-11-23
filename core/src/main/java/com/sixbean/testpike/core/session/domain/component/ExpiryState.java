package com.sixbean.testpike.core.session.domain.component;

import java.time.LocalDateTime;

/**
 * The four-state validity light for one login state (AV3 ledger side).
 * UNKNOWN is honest — the expiry could not be parsed from the captured material;
 * consumers must surface "validity unknown" instead of guessing a validity window.
 */
public enum ExpiryState {

    /** Expiry known, still comfortably outside the renew-lead window. */
    VALID,
    /** Expiry known, within the renew-lead window (session.renew-lead-minutes). */
    EXPIRING,
    /** Expiry known and in the past. */
    EXPIRED,
    /** Expiry could not be parsed (or was never stored) — honestly unknown. */
    UNKNOWN;

    /**
     * Pure classification against a reference instant and a lead window.
     * A null expiry is UNKNOWN regardless of time; the lead only applies forward.
     */
    public static ExpiryState classify(LocalDateTime expiresAt, LocalDateTime now,
                                       java.time.Duration renewLead) {
        if (expiresAt == null || now == null) {
            return UNKNOWN;
        }
        if (!expiresAt.isAfter(now)) {
            return EXPIRED;
        }
        java.time.Duration lead = renewLead == null ? java.time.Duration.ZERO : renewLead;
        return expiresAt.isAfter(now.plus(lead)) ? VALID : EXPIRING;
    }
}
