package com.sixbean.testpike.core.webelement.api.dto;

/**
 * Outcome of one ledger retention sweep .
 *
 * @param retiredCandidates aged unverified candidates moved to retired
 * through the state machine door (audit kept)
 * @param purgedRows retired rows physically deleted after the
 * retention window, anchor stats and ledger audit
 * events removed with them
 */
public record WebElementLedgerRetentionResult(int retiredCandidates, int purgedRows) {
}
