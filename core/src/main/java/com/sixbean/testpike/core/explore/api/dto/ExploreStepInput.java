package com.sixbean.testpike.core.explore.api.dto;

/**
 *
 * @param candidatePoolJson raw interactive-candidate pool collected for the
 * page this step acted on (element-candidates
 * detail; null when the step carries no pool)
 */
public record ExploreStepInput(int seq, String stepAction, String stepResult,
                               String screenshotKey, String domSnapshotKey, String llmThinkMd,
                               String candidatePoolJson) {
}
