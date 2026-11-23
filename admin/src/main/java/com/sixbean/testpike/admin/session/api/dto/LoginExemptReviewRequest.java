package com.sixbean.testpike.admin.session.api.dto;

/**
 * Re-check payload for a login exemption declaration (AV5). The signal
 * vocabulary is owned by the core verdict table and contains no member
 * meaning "the platform solved a captcha" — the page can report what a trial
 * login observed or a human reviewer concluded, and nothing more (red
 * line 1). reviewedAt is never carried here: the stamp is a server fact.
 *
 * @param signal machine token from the core re-check vocabulary
 * @param note   optional reviewer remark (bounded server-side, never a secret)
 */
public record LoginExemptReviewRequest(
        String signal,
        String note) {
}
