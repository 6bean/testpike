package com.sixbean.testpike.aiservice.mock.infrastructure.remote;

/**
 * Consumer-side mirror of core {@code MockDraftArtifactGrantView} (draft-
 * bundle upload grant; presigned PUT door, explore evidence-grant precedent —
 * credentials never travel to this side, only the short-lived URL).
 *
 * @param jobId     the drafting job this bundle belongs to
 * @param objectKey canonical storage key to write back as result_preview_ref
 * @param uploadUrl presigned PUT url
 */
public record MockDraftArtifactGrantBrief(Long jobId, String objectKey, String uploadUrl) {
}
