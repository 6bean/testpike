package com.sixbean.testpike.core.tool.api.dto;

/**
 * Draft-card routing fact of one write tool (tool-surface clearing batch
 * stage 2).
 * <p>Why this exists: the dialog emitter used to recognise write tools by
 * memorised name literals, so every new domain needed a change in the hot
 * orchestrator and a registered-but-unlisted tool became a dead tool (the
 * dispatcher refuses its token-less call, and no card was ever emitted to
 * confirm it). The registry is the single source instead: whoever publishes a
 * write tool declares the draft kind in its exec spec, and the emitter routes
 * cards from this view.
 *
 * @param toolName tp_tool.name — the function-calling name found in the
 * ReAct transcript
 * @param objectType draft kind token carried by the draft frame; must match a
 * core {@code DraftObjectType} value, otherwise the frame
 * ledger refuses to make it confirmable (fail-closed)
 */
public record ToolDraftKindItem(String toolName, String objectType) {
}
