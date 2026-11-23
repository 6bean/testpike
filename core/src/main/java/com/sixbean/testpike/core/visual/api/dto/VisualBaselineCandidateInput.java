package com.sixbean.testpike.core.visual.api.dto;


public record VisualBaselineCandidateInput(Long projectId, String stepKey, Long sourceTaskId,
                                           String sourceArtifactKey, Double diffTolerance) {
}
