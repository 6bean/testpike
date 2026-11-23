package com.sixbean.testpike.core.mcp.domain.component;

/**
 * Persisted state of a pending in-session confirmation grant (Redis value JSON).
 * <p>The envelope carries the exact drafted write so accepting it cannot drift from
 * what the caller was shown: the landing handler replays {@code payloadJson} verbatim.
 *
 * @param domain     landing domain literal (must match a registered handler at issue)
 * @param payloadJson canonical tool payload JSON, replayed into the landing handler
 * @param summary    three-part plain-language digest shown in the conversation
 * @param projectId  owning project for the audit row
 * @param actorId    acting user id captured at issuance (nullable)
 */
public record McpConfirmEnvelope(String domain, String payloadJson, String summary,
                                 long projectId, Long actorId) {
}
