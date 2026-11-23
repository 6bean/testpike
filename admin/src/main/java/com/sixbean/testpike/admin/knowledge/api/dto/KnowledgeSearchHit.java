package com.sixbean.testpike.admin.knowledge.api.dto;


public record KnowledgeSearchHit(String chunkId, Long docId, Long caseId, String sourceType,
                                 String title, String content, double similarity, double score) {
}
