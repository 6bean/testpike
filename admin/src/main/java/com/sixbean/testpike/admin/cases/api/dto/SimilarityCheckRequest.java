package com.sixbean.testpike.admin.cases.api.dto;


public record SimilarityCheckRequest(Long projectId, String queryText, Long excludeCaseId) {
}
