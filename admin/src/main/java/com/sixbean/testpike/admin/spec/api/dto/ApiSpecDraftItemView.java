package com.sixbean.testpike.admin.spec.api.dto;

import java.util.List;


public record ApiSpecDraftItemView(
        String endpointKey,
        String method,
        String path,
        String title,
        String description,
        List<String> tags,
        String paramsSkeletonJson,
        String requestContentType,
        String bodySkeleton,
        String assertionsJson,
        String negativeAssertionsJson,
        boolean negativeSelected,
        List<String> securityRefs,
        String authHintJson,
        String channel,
        String confidence,
        String sourceAnchor) {
}
