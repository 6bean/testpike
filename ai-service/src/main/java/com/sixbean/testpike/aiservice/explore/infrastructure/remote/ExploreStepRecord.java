package com.sixbean.testpike.aiservice.explore.infrastructure.remote;

/**
 * Remote payload for the exploration step write-back, mirroring core
 * {@code explore.api.dto.ExploreStepInput} field-by-field over the JSON wire
 * (ai-service must not depend on the core module, so the mirror is local —
 * precedent: ExploreSessionBrief).
 * <p>stepAction/stepResult = JSON strings (core validates on write into JSONB;
 * malformed = 40001); screenshotKey/domSnapshotKey = evidence object keys
 * (nullable, upload chain from Step2b); candidatePoolJson = raw interactive
 * candidate pool detail carried by an element-candidates step (WE-S3 anchor
 * reconciliation; null otherwise).
 */
public record ExploreStepRecord(int seq, String stepAction, String stepResult,
                                String screenshotKey, String domSnapshotKey, String llmThinkMd,
                                String candidatePoolJson) {
}
