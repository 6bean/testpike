package com.sixbean.testpike.core.behavior.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Pre-release regression verdict for one candidate prompt-template version
 * : the golden pack was replayed through the control
 * and the candidate on the same model, and the output drift rate was weighed
 * against the blocking threshold. This is the ledger row the promotion gate
 * reads — the post-release outcome ledger (first round item 7) stays the
 * scorecard after go-live; this verdict is the gate before it.
 *
 * @param id ledger row id
 * @param templateDomain prompt template family domain
 * @param templateName prompt template family name
 * @param candidateVersionId template row id entering gray (the tested version)
 * @param controlVersionId template row id used as control (current full release)
 * @param packName golden pack (judge eval-set) name the replay consumed
 * @param packVersion golden pack version pinned by its snapshot digest
 * @param snapshotDigest SHA-256 of the pack version, freezes which samples ran
 * @param sampleTotal pairs compared
 * @param driftedCount pairs whose normalized outputs differ
 * @param driftPercentPp driftedCount / sampleTotal in percentage points
 * @param thresholdPp blocking threshold in force when recorded (K8, hot-reloaded)
 * @param passed true when the drift rate does not exceed the threshold
 * @param note replay-side remark (model pinning, judge rule summary)
 * @param createdAt record time
 * @param comparisonKind axis that was replayed: template / route / head
 * @param controlRef non-template axes name the control subject (model route row or
 * assembler head); null on the template axis
 * @param candidateRef non-template axes name the candidate subject; null on the template axis
 */
public record GoldenDriftVerdict(
        Long id,
        String templateDomain,
        String templateName,
        long candidateVersionId,
        long controlVersionId,
        String packName,
        Integer packVersion,
        String snapshotDigest,
        int sampleTotal,
        int driftedCount,
        BigDecimal driftPercentPp,
        int thresholdPp,
        boolean passed,
        String note,
        LocalDateTime createdAt,
        String comparisonKind,
        String controlRef,
        String candidateRef) {

    /** Pre-axis shape : reads as a template-axis verdict with no refs. */
    public GoldenDriftVerdict(Long id, String templateDomain, String templateName, long candidateVersionId,
                              long controlVersionId, String packName, Integer packVersion,
                              String snapshotDigest, int sampleTotal, int driftedCount,
                              BigDecimal driftPercentPp, int thresholdPp, boolean passed,
                              String note, LocalDateTime createdAt) {
        this(id, templateDomain, templateName, candidateVersionId, controlVersionId, packName,
                packVersion, snapshotDigest, sampleTotal, driftedCount, driftPercentPp,
                thresholdPp, passed, note, createdAt, "template", null, null);
    }

    /** Verdict without the ledger id (used when recording a fresh comparison). */
    public GoldenDriftVerdict(String templateDomain, String templateName, long candidateVersionId,
                              long controlVersionId, String packName, Integer packVersion,
                              String snapshotDigest, int sampleTotal, int driftedCount,
                              BigDecimal driftPercentPp, int thresholdPp, boolean passed,
                              String note, LocalDateTime createdAt) {
        this(null, templateDomain, templateName, candidateVersionId, controlVersionId, packName,
                packVersion, snapshotDigest, sampleTotal, driftedCount, driftPercentPp,
                thresholdPp, passed, note, createdAt, "template", null, null);
    }
}
