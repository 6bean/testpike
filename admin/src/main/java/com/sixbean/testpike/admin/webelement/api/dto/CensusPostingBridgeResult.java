package com.sixbean.testpike.admin.webelement.api.dto;

/**
 * Bridge answer of one census-posting round : the flat
 * counts of the internal door, verbatim. Both posting kinds ride this one
 * shape so the page can show the same idempotency account wording after a
 * ledger-creation run and a reference-replacement run.
 */
public record CensusPostingBridgeResult(int entries, int posted, int alreadyPending,
                                        int alreadyLedgered, int scriptsScanned,
                                        int skippedNotLedgered) {
}
