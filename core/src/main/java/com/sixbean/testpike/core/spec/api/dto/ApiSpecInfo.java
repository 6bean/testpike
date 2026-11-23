package com.sixbean.testpike.core.spec.api.dto;

import com.sixbean.testpike.core.spec.domain.component.ApiEndpoint;
import com.sixbean.testpike.core.spec.domain.component.SpecDiff;

/**
 *
 * @param id tp_api_spec.id
 * @param specFormat openapi3-json | openapi3-yaml
 */
public record ApiSpecInfo(Long id, Long projectId, Long documentId, String name,
                          String specFormat, String specTitle, String contentSha256,
                          int specVersion, java.util.List<ApiEndpoint> endpoints,
                          Long prevSpecId, SpecDiff diffSummary,
                          Long createdBy, String createdAt) {
}
