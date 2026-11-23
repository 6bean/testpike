package com.sixbean.testpike.core.webelement.api.dto;

import java.util.List;

/**
 * Page-level roll-up of the ledger: all elements that share one folded page
 * key, with lifecycle counts so the ledger page can show "3 confirmed / 2
 * candidate" per page without the client re-counting rows.
 */
public record WebElementPageView(String pageKey, List<WebElementView> elements, int total,
                                 int candidateCount, int confirmedCount, int staleCount,
                                 int retiredCount) {
}
