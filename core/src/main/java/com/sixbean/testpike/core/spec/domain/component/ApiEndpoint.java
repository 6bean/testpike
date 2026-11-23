package com.sixbean.testpike.core.spec.domain.component;

import java.util.List;
import java.util.Map;

/**
 * <p>deprecated = OpenAPI lifecycle note; the inventory coverage denominator
 * drops retired endpoints on it. Also excluded from the structure-hash material:
 * toggling a deprecation note alone must never fire a contract "changed" badge,
 * and legacy snapshots without the key deserialize to false.
 */
public record ApiEndpoint(
        String method,
        String path,
        String endpointKey,
        String operationId,
        String title,
        String description,
        List<String> tags,
        List<ApiParameter> parameters,
        String requestContentType,
        String requestSchema,
        Map<String, String> responseSchemas,
        List<String> securityRefs,
        boolean deprecated,
        String structureHash) {
}
