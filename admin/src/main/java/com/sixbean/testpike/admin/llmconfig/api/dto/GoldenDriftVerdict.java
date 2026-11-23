package com.sixbean.testpike.admin.llmconfig.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Admin mirror of the core golden-drift ledger row .
 * Component names match the core internal contract one-to-one because the
 * admin-to-core bridge deserializes by name; a renamed or dropped component
 * silently blanks a ledger column on the page.
 *
 * @param id ledger row id
 * @param templateDomain prompt template family domain
 * @param templateName prompt template family name
 * @param candidateVersionId template row id entering gray (0 on non-template axes)
 * @param controlVersionId template row used as control (0 on non-template axes)
 * @param packName golden pack name the replay consumed
 * @param packVersion golden pack version pinned by its snapshot digest
 * @param snapshotDigest SHA-256 of the pack version, freezes which samples ran
 * @param sampleTotal pairs compared
 * @param driftedCount pairs whose normalized outputs differ
 * @param driftPercentPp driftedCount / sampleTotal in percentage points
 * @param thresholdPp blocking threshold in force when recorded
 * @param passed true when the drift rate does not exceed the threshold
 * @param note replay-side remark
 * @param createdAt record time
 * @param comparisonKind replay axis: template / route / head
 * @param controlRef control subject name on non-template axes (model route row)
 * @param candidateRef candidate subject name on non-template axes
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
}
