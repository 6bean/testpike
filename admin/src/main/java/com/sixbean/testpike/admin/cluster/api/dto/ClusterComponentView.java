package com.sixbean.testpike.admin.cluster.api.dto;

import java.time.LocalDateTime;


public record ClusterComponentView(
        Long id,
        String nodeId,
        String componentFamily,
        String currentVersion,
        String targetVersion,
        String requirement,
        String supportState,
        String stateReason,
        String source,
        LocalDateTime reportedAt,
        /** Third-party homepage (core-resolved single source; null = honest empty). */
        String homepageUrl) {
}
