package com.sixbean.testpike.admin.requirement.api.dto;

import java.time.LocalDateTime;

/**
 *
 * <p>sourceAnchor is the recorded provenance of the point (document id + chunk ids +
 * verbatim excerpt); the requirement list reads it to offer the view-original door and
 * to state honestly why a hand-made point has nothing to show.</p>
 */
public record RequirementView(Long id, Long projectId, Integer source, String sourceId,
                              String title, String acceptanceCriteria, Integer status,
                              String chapter, Long createdBy, LocalDateTime createdAt,
                              LocalDateTime updatedAt, Integer version, String fingerprint,
                              RequirementSourceAnchorView sourceAnchor) {
}
