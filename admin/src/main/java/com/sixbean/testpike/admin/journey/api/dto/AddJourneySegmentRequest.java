package com.sixbean.testpike.admin.journey.api.dto;

/**
 * Append-segment request (admin side of the bridge).
 * Serialized as-is toward core {@code JourneySegmentInput} — same JSON field names.
 *
 * @param seqNo              order position inside the journey (duplicates rejected by core)
 * @param projectId          owning project of the segment (data stays project-owned)
 * @param targetKind         "asset" (published shared-asset version row) or "case"
 * @param assetRowId         tp_asset version row id when targetKind = asset
 * @param caseId             case id when targetKind = case
 * @param paramsOverrideJson segment execution params (caseIds / envId for asset targets)
 */
public record AddJourneySegmentRequest(int seqNo, long projectId, String targetKind,
                                       Long assetRowId, Long caseId, String paramsOverrideJson) {
}
