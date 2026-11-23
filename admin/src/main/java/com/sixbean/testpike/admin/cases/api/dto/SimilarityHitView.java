package com.sixbean.testpike.admin.cases.api.dto;


public record SimilarityHitView(Long caseId, String title, double similarity,
                                SimilarityHintLevel level, String content) {
}
