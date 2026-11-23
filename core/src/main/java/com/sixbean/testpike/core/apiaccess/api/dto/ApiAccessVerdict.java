package com.sixbean.testpike.core.apiaccess.api.dto;

/**
 * Verdict of the pass-through policy.
 *
 * @param allowed true when the door may dispatch
 * @param reason  what blocked the call, phrased so the dialog can act on it
 *                (which switch, which registration, which alternative door); null when allowed
 */
public record ApiAccessVerdict(
        boolean allowed,
        String reason) {

    /** Allow verdict. */
    public static ApiAccessVerdict allow() {
        return new ApiAccessVerdict(true, null);
    }

    /** Deny verdict with the reason the caller needs. */
    public static ApiAccessVerdict deny(String reason) {
        return new ApiAccessVerdict(false, reason);
    }
}
