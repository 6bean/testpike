package com.sixbean.testpike.core.webelement.api.dto;

/**
 * One direct-heal evidence row relayed by the executor (03 protocol 2.4
 * trailing key stepHealEvidence, Page Element Library self-heal ledger).
 * The executor only records what happened during a self-heal — which
 * element healed, which anchor failed first, which anchor won, at what
 * visual similarity and what ledger revision; core stores these rows
 * verbatim as the audit trail behind a healed run and never re-scores
 * them here (the key set is bilaterally pinned with the executor's
 * HEAL_EVIDENCE_ROW_KEYS constant, so a rename on either side turns the
 * contract gate red instead of silently dropping the column).
 *
 * @param healed            always true on this channel — the row exists
 *                          because a fallback anchor resolved the element
 * @param firstAnchorFailed always true alongside healed: the primary
 *                          anchor was tried and missed
 * @param elementId         ledger element that was healed (nullable — the
 *                          heal may come from a candidate without an
 *                          addressable ledger row)
 * @param stepNo            action step index where the heal happened
 * @param fromAnchor        the anchor that failed (type/value half of the
 *                          locator vocabulary, same shape as the ledger)
 * @param usedAnchor        the anchor that healed the step
 * @param viewport          execution bucket label (e.g. 1440x900)
 * @param ledgerRevision    element-ledger revision at report time
 * @param similarityScore   visual similarity vs the baseline screenshot;
 *                          null = no baseline was comparable (unproven)
 */
public record StepHealEvidenceInput(Boolean healed, Boolean firstAnchorFailed,
                                    Long elementId, Integer stepNo,
                                    WebElementAnchorInput fromAnchor,
                                    WebElementAnchorInput usedAnchor,
                                    String viewport, Integer ledgerRevision,
                                    Double similarityScore) {
}
