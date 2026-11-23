package com.sixbean.testpike.core.session.api.dto;

import java.util.List;

/**
 * Executor answer for the commit round . Either {@code entries}
 * carries the values for the checked subset, or {@code failureReason} says
 * honestly why the browser session could no longer be reached — the operator
 * logged out or the browser was closed, so the session is terminally
 * invalidated and the wizard falls back to the re-open wording (never a
 * silent success, never a fake empty ledger write).
 */
public record CaptureCommitValues(
        List<CaptureCommittedEntry> entries,
        String failureReason) {
}
