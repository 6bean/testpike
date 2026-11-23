package com.sixbean.testpike.core.cluster.api.dto;

import java.time.LocalDateTime;

/**
 * Component version ledger view (single row of tp_cluster_component; version detection data plane).
 * <p>supportState value range is GREEN/YELLOW/RED/UNKNOWN (see ComponentSupportRules for the decision spectrum).
 * The view layer prohibits secondary decisions and uses a single-source standard.
 */
public record ClusterComponentInfo(
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
        /** Third-party homepage resolved core-side (single source); null = no external homepage. */
        String homepageUrl) {
}
