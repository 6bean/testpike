package com.sixbean.testpike.admin.knowledge.api.dto;


public record KnowledgeSearchParams(Long projectId, String query, String scene, Integer topK) {
}
