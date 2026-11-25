package com.sixbean.testpike.core.mock.api.dto;

import java.util.List;

/**
 * Intake body of the AI advice backfill round for one contract-bulk preview
 * (MJ P4/D6): only the spec-enrich job family calls this door, and the core
 * single point still validates every row (unknown endpoint keys and illegal
 * verdicts are skipped, stored rows are refined in place, never duplicated).
 */
public record SpecVerdictRefineInput(long specId, long spaceId, List<SpecVerdictRefineRow> refined) {

    /** Map onto the stored-row shape; identities the AI round may not touch
     *  (conflict-stub id, creation anchor) keep their persisted values. */
    public List<SpecImportVerdict> toVerdictRows() {
        if (refined == null) {
            return List.of();
        }
        return refined.stream()
                .filter(java.util.Objects::nonNull)
                .map(row -> new SpecImportVerdict(row.endpointKey(), row.verdict(), row.reason(),
                        row.conflictAction(), null, row.impactJson(), row.realism(), row.confidence(),
                        SpecImportVerdict.ORIGIN_AI, 0L))
                .toList();
    }
}
