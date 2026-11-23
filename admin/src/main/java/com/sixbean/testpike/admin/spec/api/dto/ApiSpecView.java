package com.sixbean.testpike.admin.spec.api.dto;

import java.util.List;


public record ApiSpecView(Long id, Long projectId, Long documentId, String name,
                          String specFormat, String specTitle, String contentSha256,
                          int specVersion, List<ApiEndpointView> endpoints,
                          Long prevSpecId, SpecDiffView diffSummary,
                          Long createdBy, String createdAt) {
}
