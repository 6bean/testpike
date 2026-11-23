package com.sixbean.testpike.core.cases.api.dto;

import java.time.LocalDateTime;

/**
 * Flaky restore proposal surfaced to the cases page (AO batch , read channel
 * of the re-probe ledger).
 * <p>One entry per state-1 session row: the ledger already verified
 * {@code greenStreak} post-quarantine green runs, {@code greenThreshold} rides the live
 * value of the hot key {@code ai.flaky.probe.green-count} re-read per call (retuning shows
 * up immediately without touching the ledger), and {@code proposedAt} is when the proposal
 * was raised. This is a suggestion for human confirmation only - clearing the flaky mark
 * stays the existing PUT /cases/{id}/flaky door, and no write path consumes this shape.
 */
public record FlakyRestoreProposal(long caseId, String caseNo, String caseTitle,
                                   int greenStreak, int greenThreshold,
                                   LocalDateTime proposedAt) {
}
