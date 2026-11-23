package com.sixbean.testpike.admin.visual.api.dto;


public record VisualBaselineView(Long id, Long projectId, String stepKey, Long sourceTaskId,
                                 String sourceArtifactKey, String baselineKey, int status,
                                 String statusLabel, Double diffTolerance, Long confirmedBy,
                                 String createdAt, String confirmedAt) {
}
