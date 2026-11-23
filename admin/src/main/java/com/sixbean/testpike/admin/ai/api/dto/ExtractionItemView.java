package com.sixbean.testpike.admin.ai.api.dto;


public record ExtractionItemView(long id, String title, String acceptanceCriteria,
                                 String chapter, Double confidence,
                                 boolean selected, Long requirementId,
                                 String evidence, Long chunkId) {
}
