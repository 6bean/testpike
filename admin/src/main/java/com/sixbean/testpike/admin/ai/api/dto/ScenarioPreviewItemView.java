package com.sixbean.testpike.admin.ai.api.dto;

/**
 * One stage0 scenario of the circle-selection preview .
 * <p>Mirror of the core row shape (field names bind by name across the bridge);
 * {@code kind} stays a plain wire string here — the hyphen-code vocabulary is
 * owned by the ai-service enumeration, the admin plane never re-declares it.
 *
 * @param kind scenario kind wire code (main-flow / equivalence / counter-example / boundary / state-combination)
 * @param title short scenario name shown in the selection list
 * @param anchor verbatim fragment of the requirement line the scenario is grounded on
 * @param note optional one-line rationale shown next to the scenario
 */
public record ScenarioPreviewItemView(String kind, String title, String anchor, String note) {
}
