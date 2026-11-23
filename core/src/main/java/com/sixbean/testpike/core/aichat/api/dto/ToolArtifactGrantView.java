package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Upload-grant view for one verbatim tool-result artifact ;
 * data segment of GET /internal/ai-chat/tool-artifact/grant — mirrors the
 * explore evidence grant shape, but the scene is system level so no project
 * segment is echoed back).
 *
 * @param objectKey canonical storage key (written into the folded window as
 * the retrieval anchor)
 * @param uploadUrl presigned PUT url (system default route, storage.url-ttl)
 */
public record ToolArtifactGrantView(String objectKey, String uploadUrl) {
}
