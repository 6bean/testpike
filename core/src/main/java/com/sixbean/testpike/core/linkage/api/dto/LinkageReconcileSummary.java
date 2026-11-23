package com.sixbean.testpike.core.linkage.api.dto;

import java.util.List;

/**
 * One round of the daily bus-edge reconciliation (AN batch , segment A
 * segment 4). A thin findings face over {@link OrphanEdgeScanReport} that
 * adds the single number the operator reads first: {@code findingsTotal},
 * the count of ledger items that need a human look. The job never mutates
 * anything, so this DTO stays a pure report — downstream surfaces (health
 * card, reminder escalation in ) read counts from here instead of
 * re-deriving verdicts from raw rows (single-source posture).
 *
 * @param armedMembers bus member tokens currently armed in the
 * edge ledger, passed through from the scan
 * @param orphans foreign-key columns on armed members with
 * no registered edge covering them
 * @param deadEdges edge keys whose upstream token is not a bus
 * member, so no save can ever arm them
 * @param busMembersWithoutEdges bus member tokens with no enabled edge row
 * at all (coverage still owed by the batch plan)
 * @param coveredForeignKeys number of swept foreign keys that ARE covered
 * @param findingsTotal orphans + deadEdges + busMembersWithoutEdges,
 * zero means the ledger and the live schema
 * agree right now
 */
public record LinkageReconcileSummary(
        List<String> armedMembers,
        List<LinkageOrphanEdge> orphans,
        List<String> deadEdges,
        List<String> busMembersWithoutEdges,
        int coveredForeignKeys,
        int findingsTotal) {

    /** Derive the report face from one scan round (the scan is the verdict source). */
    public static LinkageReconcileSummary from(OrphanEdgeScanReport report) {
        return new LinkageReconcileSummary(
                report.armedMembers(),
                report.orphans(),
                report.deadEdges(),
                report.busMembersWithoutEdges(),
                report.coveredForeignKeys(),
                report.orphans().size() + report.deadEdges().size()
                        + report.busMembersWithoutEdges().size());
    }
}
