package com.sixbean.testpike.admin.knowledge.api.dto;

/**
 *
 * @param status ACTIVE/INACTIVE
 */
public record KnowledgeDocView(Long id, Long projectId, Long packId, String sourceType,
                               Long sourceRef, String title, String content, String status,
                               String createdAt, String updatedAt) {
}
