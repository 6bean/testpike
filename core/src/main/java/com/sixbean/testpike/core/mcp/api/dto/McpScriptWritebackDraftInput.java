package com.sixbean.testpike.core.mcp.api.dto;

import com.sixbean.testpike.core.script.api.dto.ScriptWritebackEvidence;

/**
 * Request body of an off-platform script write-back draft (MCP in-session gate).
 * <p>The draft changes nothing on arrival: it is bound to a one-shot confirmation
 * reference and only settles through the landing handler after a human decision.
 *
 * @param projectId       owning project (membership already checked on the entry channel)
 * @param caseId          target case
 * @param schemaVersion   action JSON schema version (v1/v2, 04 contract)
 * @param actionJson      agent-authored action JSON
 * @param evidence        evidence package; null = bare self-report (grade 1 on landing)
 * @param expectedVersion optimistic-lock expected script version; null = skip comparison
 */
public record McpScriptWritebackDraftInput(long projectId, long caseId, String schemaVersion,
                                           String actionJson, ScriptWritebackEvidence evidence,
                                           Integer expectedVersion) {
}
