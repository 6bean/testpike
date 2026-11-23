package com.sixbean.testpike.admin.cases.api.dto;

/**
 * Canvas node to action-step mapping entry (W5 APP phase 2, UI Flow canvas view; isomorphic mirror
 * of the core FlowchartNode — the JSON shape is deserialized directly, no manual conversion layer).
 * <p>{@code id} = mermaid node identifier as emitted by the core assembler; {@code stepIndex} =
 * 1-based position among action nodes in document pre-order. Canvas consumers resolve a clicked node
 * through this pair only, so the traversal rule stays single-sourced on the server.
 */
public record FlowchartNodeView(String id, int stepIndex) {
}
