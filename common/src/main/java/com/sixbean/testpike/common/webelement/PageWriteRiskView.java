package com.sixbean.testpike.common.webelement;

/**
 * Narrow risk row of the page write-risk ledger: exactly what
 * the ai-service explore kernel needs to annotate its prompt and gate a
 * click/input dispatch — the page identity and its URL, nothing heavier.
 * The full decoded windows stay on the paged door the screen rides, so the
 * kernel read stays bounded no matter how many events a page has booked.
 */
public record PageWriteRiskView(String pageKey, String pageUrl) {
}
