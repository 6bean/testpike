package com.sixbean.testpike.admin.knowledge.api.dto;

import java.util.List;

/**
 *
 * @param status ACTIVE/INACTIVE
 */
public record KnowledgeDocDetailView(Long id, Long projectId, Long packId, String sourceType,
                                     Long sourceRef, String title, String content, String status,
                                     String createdAt, String updatedAt, int hitCount,
                                     List<KnowledgeChunkView> chunks) {
}
