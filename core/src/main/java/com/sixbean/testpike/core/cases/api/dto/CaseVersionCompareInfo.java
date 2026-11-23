package com.sixbean.testpike.core.cases.api.dto;

/**
 * One-line compare card answer . The
 * dialog asks "compare vA and vB" and this is what the model receives:
 * WHICH fields moved (labels only, steps broken down into stepNo-aligned
 * added/removed/modified counts) — deliberately NOT the raw before/after
 * values, because a card that dumps the snapshots fails the "one human
 * line, no JSON dump" contract the batch froze.
 *
 * @param caseId compared case
 * @param fromVersion resolved baseline version actually used
 * @param toVersion target version of the card
 * @param truncated true when the span exceeds the shared diff door's
 * 20-version limit (hint already inside summary)
 * @param changedFieldCount number of changed fields (steps = one family)
 * @param summary the single human-readable line shown on the card
 */
public record CaseVersionCompareInfo(long caseId, int fromVersion, int toVersion,
                                     boolean truncated, int changedFieldCount, String summary) {
}
