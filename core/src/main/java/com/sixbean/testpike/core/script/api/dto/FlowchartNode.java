package com.sixbean.testpike.core.script.api.dto;

/**
 * Canvas node to action-step mapping entry (W5 APP phase 2, UI Flow canvas view).
 * <p>{@code id} = mermaid node identifier emitted by the flowchart assembler (s1..sN, creation
 * order — the assembler is the single source of the numbering); {@code stepIndex} = 1-based
 * position among action nodes in document pre-order (if/loop containers count as one step,
 * rendering helper nodes such as branch labels / join / loop-end never appear).
 * <p>Purpose: canvas consumers translate "clicked node id" to "the step it projects" through this
 * mapping only — re-deriving the traversal on another surface would duplicate the assembler rule.
 */
public record FlowchartNode(String id, int stepIndex) {
}
