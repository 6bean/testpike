package com.sixbean.testpike.core.webelement.api.dto;

/**
 * Result of one script reference-replacement posting round :
 * survey elements seen, scripts inspected for the elements that already live
 * in the page element library, replacement cards newly filed, and the two
 * honest exclusion counters — elements not yet in the library, and scripts
 * with a card still pending. The numbers double as the idempotency account:
 * a re-post on an unchanged state must report {@code posted = 0}, and once
 * cards landed the census itself stops flagging the replaced scripts
 * ({@code entries} falls accordingly — replacement verified by the survey).
 */
public record WebElementScriptRefPosting(int entries,
                                         int scriptsScanned,
                                         int posted,
                                         int skippedNotLedgered,
                                         int alreadyPending) {
}
