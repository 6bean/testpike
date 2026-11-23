package com.sixbean.testpike.core.session.api.dto;

/**
 * Observation reported by a trial-login re-check of a login exemption
 * declaration (AV5). The vocabulary is deliberately limited to what a
 * non-adversarial trial can genuinely observe: whether a captcha challenge
 * still appeared, whether the declared master code was accepted, or whether
 * the trial could not conclude. There is no signal meaning "the platform
 * solved a captcha" — that capability is permanently out of scope (red line
 * 1: zero commitment on the adversarial surface).
 *
 * @param signal machine token from LoginExemptReviewSupport.SIGNAL_*
 * @param note   optional human note recorded with the verdict (truncated by
 *               the server; never a secret value)
 */
public record LoginExemptReviewSignal(String signal, String note) {

    public LoginExemptReviewSignal {
        if (note != null && note.length() > 2000) {
            throw new IllegalArgumentException("review note too long");
        }
    }
}
