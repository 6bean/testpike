package com.sixbean.testpike.core.behavior.api.dto;

import java.util.List;

/**
 * Body for recording one golden-set replay verdict .
 * Produced by the replay side (the judge/sample pipeline running the same model
 * over the domain's golden pack for two template versions) and posted to the
 * core gate ledger. The drift statistics are re-derived server-side from the
 * pairs; nothing in this DTO asserts its own pass rate.
 *
 * @param templateDomain prompt template family domain
 * @param templateName prompt template family name
 * @param candidateVersionId template row id entering evaluation (gray version)
 * @param controlVersionId template row id used as control (current full release)
 * @param packName golden pack (judge eval-set) name replayed
 * @param packVersion golden pack version pinned by its snapshot digest
 * @param snapshotDigest SHA-256 hex of the pack version content
 * @param pairs per-sample output digests for the two versions
 * @param note replay-side remark (model pinning, judge rules used)
 * @param comparisonKind replay axis: template (default when absent) / route / head
 *
 * @param controlRef non-template axes: name of the control subject (model route
 * row or assembler head); ignored on the template axis
 * @param candidateRef non-template axes: name of the candidate subject
 */
public record GoldenComparisonInput(
        String templateDomain,
        String templateName,
        Long candidateVersionId,
        Long controlVersionId,
        String packName,
        Integer packVersion,
        String snapshotDigest,
        List<GoldenDriftPair> pairs,
        String note,
        String comparisonKind,
        String controlRef,
        String candidateRef) {
}
