package com.sixbean.testpike.core.ai.api.dto;

/**
 * One scenario of the stage0 enumeration preview . Mirror of
 * the ai-service enumeration item shape kept core-side so the preview ledger,
 * the circle-selection read path and the case tables never share a mutable
 * type across module boundaries.
 *
 * @param kind scenario kind (main-flow / equivalence / counter-example /
 * boundary / state-combination)
 * @param title short scenario name shown in the selection list
 * @param anchor verbatim fragment of the requirement line the scenario is
 * grounded on (empty never survives the parser)
 * @param note optional extra remark, may be null
 */
public record ScenarioPreviewItem(String kind, String title, String anchor, String note) {
}
