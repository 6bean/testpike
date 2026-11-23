package com.sixbean.testpike.core.webelement.api.dto;

import java.util.List;

/**
 * One ledger row frozen for dispatch (WE batch S1). The anchor list is already
 * ordered by the server-side ranking formula — the executor tries them in this
 * exact order and must not re-score (formula single source, red line 6).
 *
 * @param elementId ledger row id (feedback reflow target)
 * @param pageKey folded page key the element belongs to
 * @param state lifecycle state at dispatch time (0=candidate 1=confirmed)
 * @param confidence feedback recomputed hit quality at dispatch time
 * @param anchors ordered anchor set, strongest first — for a viewport-class
 * declaration this is that class's variant set ordered by the
 * same formula, falling back to the top-level set when the
 * class is undeclared or unmatched (design §8.2)
 * @param variants per-viewport-class anchor sets as stored (design §8.2),
 * each ordered by the same formula; empty = single-viewport
 * asset, executor keeps using the top-level anchors
 * @param revision optimistic-lock revision of the ledger row at dispatch
 * time : direct-heal evidence carries it so
 * the result-side write back refuses a stale promotion
 * with the shared 40901 semantics instead of overwriting
 * a concurrently edited row
 */
public record ElementSnapshot(long elementId, String pageKey, int state, double confidence,
                              List<WebElementAnchorInput> anchors,
                              List<WebElementVariantInput> variants, int revision) {
}
