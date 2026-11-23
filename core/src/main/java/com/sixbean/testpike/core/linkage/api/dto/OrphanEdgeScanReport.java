package com.sixbean.testpike.core.linkage.api.dto;

import java.util.List;

/**
 * Outcome of one orphan-edge scan round . Every list is sorted
 * so two runs against an unchanged database report identically and the daily
 * reconciliation can diff counts without racing row order.
 *
 * @param armedMembers bus member tokens with at least one enabled
 * non-exempt edge, the only members whose
 * foreign keys are swept
 * @param orphans foreign-key columns on armed members with no
 * registered edge covering them
 * @param deadEdges edge keys whose upstream token is not a bus
 * member, so no save can ever arm them
 * @param busMembersWithoutEdges bus member tokens with no enabled edge row at
 * all (coverage still owed by the batch plan)
 * @param coveredForeignKeys number of swept foreign keys that ARE covered
 */
public record OrphanEdgeScanReport(
        List<String> armedMembers,
        List<LinkageOrphanEdge> orphans,
        List<String> deadEdges,
        List<String> busMembersWithoutEdges,
        int coveredForeignKeys) {
}
