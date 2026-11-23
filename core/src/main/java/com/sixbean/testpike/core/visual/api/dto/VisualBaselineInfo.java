package com.sixbean.testpike.core.visual.api.dto;


public record VisualBaselineInfo(Long id, Long projectId, String stepKey, Long sourceTaskId,
                                 String sourceArtifactKey, String baselineKey, int status,
                                 String statusLabel, Double diffTolerance, Long confirmedBy,
                                 String createdAt, String confirmedAt) {
}
