package com.sixbean.testpike.aiservice.knowledge.api.dto;


public record KnowledgeSearchRequest(Long projectId, String query, String scene, Integer topK,
                                     Long taskId) {
}
