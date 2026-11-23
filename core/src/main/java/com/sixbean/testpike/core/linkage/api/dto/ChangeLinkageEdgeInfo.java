package com.sixbean.testpike.core.linkage.api.dto;

import java.time.LocalDateTime;

/**
 * Change-linkage edge as stored, read back by the linkage bus and by the
 * edge-ledger screens. The registered definition travels inside
 * {@link #definition()} (composition — the field list is declared once); this
 * record adds only identity and lifecycle columns around it.
 *
 * @param id        row id
 * @param enabled   1=active edge, 0=kept for audit but not dispatched
 * @param createdAt when the edge was first registered
 * @param updatedAt last real change; identical re-registration must not move
 *                  it (a replay is not a change fact)
 */
public record ChangeLinkageEdgeInfo(
        Long id,
        Integer enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        ChangeLinkageEdgeInput definition) {
}
