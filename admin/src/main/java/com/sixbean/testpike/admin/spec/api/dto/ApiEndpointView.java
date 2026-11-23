package com.sixbean.testpike.admin.spec.api.dto;

import java.util.List;
import java.util.Map;


public record ApiEndpointView(
        String method,
        String path,
        String endpointKey,
        String operationId,
        String title,
        List<ApiParameterView> parameters,
        String requestContentType,
        String requestSchema,
        Map<String, String> responseSchemas,
        List<String> securityRefs,
        String structureHash) {
}
