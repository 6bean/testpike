package com.sixbean.testpike.core.spec.domain.component;

import java.util.List;


public record ApiSpecDraftItem(
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
