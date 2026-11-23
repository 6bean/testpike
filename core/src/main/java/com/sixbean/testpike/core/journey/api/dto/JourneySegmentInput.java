package com.sixbean.testpike.core.journey.api.dto;

/**
 * One ordered segment of a journey plan . Exactly one target is present:
 * {@code assetRowId} points at a published shared-asset version row of any project, while
 * {@code caseId} points at a case inside {@code projectId}. The segment's owning project
 * decides which project the expanded batch will belong to — journey runs never move
 * business data across the H1 project boundary.
 *
 * @param seqNo execution order inside the journey (unique per journey)
 * @param projectId owning member project of this segment
 * @param targetKind "asset" | "case"
 * @param assetRowId pinned tp_asset version row id (asset target only)
 * @param caseId target case id (case target only)
 * @param paramsOverrideJson segment-level parameter overrides merged into the batch; nullable
 */
public record JourneySegmentInput(int seqNo, long projectId, String targetKind,
                                  Long assetRowId, Long caseId, String paramsOverrideJson) {
}
