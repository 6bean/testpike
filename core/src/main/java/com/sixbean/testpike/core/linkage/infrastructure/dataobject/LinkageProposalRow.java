package com.sixbean.testpike.core.linkage.infrastructure.dataobject;

/**
 * Persistence shape of one {@code tp_linkage_proposal} row (snapshot of an AI
 * draft requested by an armed grouped-draft impact). Timestamps stay as
 * {@link java.sql.Timestamp} at this layer; the api DTO converts at the
 * service boundary.
 */
public record LinkageProposalRow(
        Long id,
        Long impactId,
        String downstreamObjectType,
        Long downstreamObjectId,
        String proposalJson,
        String schemaVersion,
        String modelName,
        Integer baseScriptVersion,
        Integer status,
        String rejectReason,
        java.sql.Timestamp createdAt,
        java.sql.Timestamp updatedAt) {
}
