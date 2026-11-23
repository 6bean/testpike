package com.sixbean.testpike.aiservice.knowledge.api.dto;


public record KnowledgeSearchItem(String chunkId, Long docId, Long caseId, String sourceType,
                                  String title, String content, double similarity, double score) {
}
