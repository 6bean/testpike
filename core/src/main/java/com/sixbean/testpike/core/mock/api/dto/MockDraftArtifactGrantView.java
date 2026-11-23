package com.sixbean.testpike.core.mock.api.dto;

/**
 * Draft-bundle upload grant for one AI drafting job (explore evidence-grant
 * precedent — separate domain record to keep executor semantics out).
 * <p>The consumer PUTs the raw draft JSON to {@code uploadUrl}, then writes
 * back {@code objectKey} as the result_preview_ref (well under the column
 * cap). Credentials never travel: the URL is presigned by core's routed
 * storage provider.
 *
 * @param jobId     the drafting job this bundle belongs to
 * @param objectKey canonical storage key (tp_mock_ai_job.result_preview_ref)
 * @param uploadUrl presigned PUT url (ttl = storage.url-ttl, project-routed)
 */
public record MockDraftArtifactGrantView(Long jobId, String objectKey, String uploadUrl) {
}
