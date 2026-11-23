package com.sixbean.testpike.core.webelement.api.dto;

/**
 * Result of one census migration posting : how many survey
 * elements were found, how many confirmation cards were newly filed and how
 * many elements already had a pending card waiting for a human decision.
 * The numbers double as the idempotency account — a re-post on an unchanged
 * survey must report {@code posted = 0}. Elements that already live in the
 * page element library are excluded before posting ({@code alreadyLedgered})
 * — re-running the census after a card landed must not re-file a card that
 * could only ever be refused by the ledger's own unique key.
 */
public record WebElementLedgerPosting(int entries, int posted, int alreadyPending,
                                      int alreadyLedgered) {
}
