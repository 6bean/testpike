package com.sixbean.testpike.core.document.domain.component;


public record DocumentExtractState(long projectId, int status, String extractError,
                                   String extractionJson, String extractToken,
                                   Long createdBy, String category) {
}
