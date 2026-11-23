package com.sixbean.testpike.admin.webelement.api.dto;

import java.util.List;

/**
 * Page-level roll-up of the ledger: every element sharing one folded page
 * key, with lifecycle counts so the ledger page can show "3 confirmed / 2
 * candidate" per page without re-counting rows in the browser.
 */
public record WebElementPageGroupView(String pageKey, List<WebElementLedgerView> elements, int total,
                                      int candidateCount, int confirmedCount, int staleCount,
                                      int retiredCount) {
}
