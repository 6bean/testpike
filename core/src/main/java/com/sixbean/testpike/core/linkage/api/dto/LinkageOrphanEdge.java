package com.sixbean.testpike.core.linkage.api.dto;

/**
 * One foreign-key column that points at an armed mutable object table but is
 * not covered by any registered edge .
 * Carries the database names verbatim so the operator can locate the schema
 * site, while {@code upstreamObjectType} is the ledger token the missing
 * edge row would have to name.
 */
public record LinkageOrphanEdge(
        String upstreamObjectType,
        String referencingTable,
        String referencedTable,
        String fkName,
        String fkColumn) {
}
