package com.sixbean.testpike.core.knowledge.api.dto;

/**
 *
 * @param revision ledger version: 1 at first ingest, +1 on every re-ingest
 * update (grounding citation audit anchor)
 */
public record KnowledgeDocItem(Long id, Long projectId, Long packId, String sourceType,
                               Long sourceRef, String title, String content, String status,
                               String createdAt, String updatedAt, Integer revision) {
}
