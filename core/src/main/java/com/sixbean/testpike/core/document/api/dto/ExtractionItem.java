package com.sixbean.testpike.core.document.api.dto;


public record ExtractionItem(long id, String title, String acceptanceCriteria,
                             String chapter, Double confidence,
                             boolean selected, Long requirementId,
                             String evidence, Long chunkId) {

    
    public ExtractionItem(long id, String title, String acceptanceCriteria,
                          String chapter, Double confidence,
                          boolean selected, Long requirementId) {
        this(id, title, acceptanceCriteria, chapter, confidence, selected, requirementId,
                null, null);
    }
}
