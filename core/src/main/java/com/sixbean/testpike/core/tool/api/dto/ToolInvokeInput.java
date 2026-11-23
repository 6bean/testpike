package com.sixbean.testpike.core.tool.api.dto;

/**
 *
 * @param assertedProjectId server-asserted project segment on the carrier (BF batch
 * : the envelope is built programmatically by the ai-service dispatcher,
 * the model only composes {@code input} and can never write this field).
 * For system-level tools with no project fact of their own it is the
 * trusted value behind the identity-tampering guard; null = no anchor,
 * where every projectId claim stays refused.
 */
public record ToolInvokeInput(Long taskId, String input, String confirmToken,
                              Long assertedProjectId) {
}
