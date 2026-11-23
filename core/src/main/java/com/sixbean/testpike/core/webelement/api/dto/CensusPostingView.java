package com.sixbean.testpike.core.webelement.api.dto;

/**
 * One census-posting round answered through the internal doors ( piece
 * 3c). The flat shape carries both posting kinds — ledger-creation cards
 * fill {@code entries}/{@code posted}/{@code alreadyPending}/{@code
 * alreadyLedgered}, reference-replacement cards fill {@code
 * scriptsScanned}/{@code skippedNotLedgered} — and every number is an
 * idempotency account the page can re-read after a re-run.
 */
public record CensusPostingView(int entries, int posted, int alreadyPending, int alreadyLedgered,
                                int scriptsScanned, int skippedNotLedgered) {
}
