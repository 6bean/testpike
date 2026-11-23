package com.sixbean.testpike.aiservice.gen.api.dto;

/**
 * One scenario of the stage0 enumeration list . The anchor is a
 * quote from the requirement's source text — a scenario without an anchor is
 * an invented scope, so the parser refuses it before the circle-selection
 * step. Note is the optional one-line rationale shown next to the scenario in
 * the selection preview (draft/preview shape only, never a written record).
 *
 * @param kind scenario kind
 * @param title what the scenario exercises (one sentence)
 * @param anchor quoted source-text fragment the scenario derives from
 * @param note optional rationale shown in the preview
 */
public record ScenarioItem(ScenarioKind kind, String title, String anchor, String note) {
}
