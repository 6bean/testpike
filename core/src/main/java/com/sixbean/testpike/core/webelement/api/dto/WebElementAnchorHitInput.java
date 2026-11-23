package com.sixbean.testpike.core.webelement.api.dto;

/**
 * One page-element anchor attempt reported back by the executor (03 protocol
 * 2.4 result trailing key elementAnchorHits, WE .
 * <p>The key set is bilaterally pinned with the executor constant
 * {@code WebDriverExecutor.ANCHOR_HIT_ROW_KEYS} by
 * executor/tests/test_element_envelope_key_contract.py — a rename on either
 * side turns that gate red instead of silently dropping a column at runtime.
 * <p>Outcome semantics (executor records, core scores — red line 6): the
 * executor only reports what happened during ordered multi-anchor tries;
 * the smoothed confidence is recomputed once core-side from these rows.
 *
 * @param elementId ledger row id resolved at dispatch time; null on the
 * per-run truncation sentinel row (never counted)
 * @param name referenced element name (audit trail only, never a key)
 * @param viewport execution bucket label, e.g. "1440x900" — stats are kept
 * per (viewport, anchorType) so desktop hits can never
 * inflate a broken mobile anchor (design §8 columnar rule)
 * @param anchorType anchor class tried: css|xpath|text|placeholder|alt|testid|rel,
 * or the "__truncated__" sentinel naming a clipped report
 * @param hit this anchor resolved on this attempt
 * @param tried the anchor was actually attempted; untried rows (e.g. rel
 * anchors pending the self-healing segment) count for nothing
 */
public record WebElementAnchorHitInput(Long elementId, String name, String viewport,
                                       String anchorType, Boolean hit, Boolean tried) {
}
