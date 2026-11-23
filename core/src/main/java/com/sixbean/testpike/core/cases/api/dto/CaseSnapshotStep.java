package com.sixbean.testpike.core.cases.api.dto;

/**
 * Embedded steps within the version snapshot (01 elements of snapshot_json.steps; isAssert 0=operation, 1=assertion).
 *
 */
public record CaseSnapshotStep(int stepNo, String description, int isAssert) {
}
