package com.sixbean.testpike.aiservice.tool.infrastructure.remote;

/**
 * Remote mirror of the core tool-result upload grant ; data
 * segment of GET /internal/ai-chat/tool-artifact/grant — ai-service must not
 * depend on the core module, so the contract is mirrored locally).
 *
 * @param objectKey canonical storage key returned as the retrieval anchor
 * @param uploadUrl presigned PUT url for the raw payload bytes
 */
public record ToolArtifactGrantBrief(String objectKey, String uploadUrl) {
}
