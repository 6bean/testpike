package com.sixbean.testpike.admin.cases.api.dto;

import java.time.LocalDateTime;

/**
 * Admin-side mirror of the core flaky restore-proposal read shape .
 * <p>Consumed by the cases page to badge quarantined cases whose re-probe ledger reached
 * the green bar; confirming the restore stays a human action through the existing
 * PUT /cases/{id}/flaky door, this view carries no write semantics.
 */
public record FlakyRestoreProposalView(long caseId, String caseNo, String caseTitle,
                                       int greenStreak, int greenThreshold,
                                       LocalDateTime proposedAt) {
}
