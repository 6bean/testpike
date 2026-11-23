package com.sixbean.testpike.core.cases.api.dto;

/**
 * The flaky daily detection task result is from an internally triggered task registered as having no external interface.
 * The detection task only marks and suggests; clearing is a manual action via PUT flaky and is not automatic.
 *
 * @param scanned number of scanned test cases (deleted=0 and with terminal execution records)
 * @param marked number newly marked as flaky in this round (reruns of already marked cases do not accumulate; only the reason is refreshed)
 * @param suggestClear number of isolated cases with five consecutive consistent recent results, for which manual clearing is suggested (not automatic)
 */
public record FlakyDetectionResult(int scanned, int marked, int suggestClear) {
}
