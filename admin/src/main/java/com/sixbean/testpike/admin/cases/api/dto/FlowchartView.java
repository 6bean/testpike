package com.sixbean.testpike.admin.cases.api.dto;

import java.util.List;

/**
 * <p>{@code nodes} carries the canvas node to action-step mapping emitted by the same core
 * assembler: it is the only channel through which a clicked canvas node resolves to the step it
 * projects, so the traversal rule stays single-sourced on the server side.
 *
 */
public record FlowchartView(String mermaid, int stepCount, List<FlowchartNodeView> nodes) {

    /**
     * Compact constructor: a payload without the mapping (empty placeholder graph, or a producer
     * predating the mapping) reads as "no mapping" instead of null — canvas consumers must never
     * branch on a null list.
     */
    public FlowchartView {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /** Construction form used by bridge stubs that carry no canvas mapping. */
    public FlowchartView(String mermaid, int stepCount) {
        this(mermaid, stepCount, List.of());
    }
}

