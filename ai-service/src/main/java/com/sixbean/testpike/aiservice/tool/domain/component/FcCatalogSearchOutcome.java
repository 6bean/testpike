package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;

import java.util.List;

/**
 * Result of one {@code search_tools} meta-tool call (AP-5, .
 *
 * @param content model-visible text: per-match name + full schema JSON
 * on success, a readable refusal on failure
 * @param success false = blank/unknown query (fail-soft, never a throw)
 * @param expandedTools ready-to-mount definitions of the matched deferred
 * tools — the ReAct loop appends these to the resident
 * list so the next round can actually call them
 */
public record FcCatalogSearchOutcome(String content, boolean success,
                                     List<ChatToolDef> expandedTools) {
}
