package com.sixbean.testpike.api.mcp.domain.component;

import java.util.List;
import java.util.Set;


public class McpToolVisibilityFilter {

    private final McpToolCatalog catalog;

    public McpToolVisibilityFilter(McpToolCatalog catalog) {
        this.catalog = catalog;
    }

    
    public List<McpToolDefinition> visibleFor(List<String> scopes, boolean readonly) {
        return visibleFor(scopes, readonly, List.of());
    }

    /**
     * Same convergence applied to a dynamically supplied family (the pass-through tools).
     *
     * <p>The extra list is an input, not a second pipeline: one filter decides visibility for
     * everything on the wire, so a family added later can never smuggle past the scope or read-only
     * rules by having its own copy of them.
     */
    public List<McpToolDefinition> visibleFor(List<String> scopes, boolean readonly,
                                              List<McpToolDefinition> extra) {
        Set<String> granted = Set.copyOf(scopes == null ? List.of() : scopes);
        List<McpToolDefinition> merged = new java.util.ArrayList<>(catalog.all());
        merged.addAll(extra == null ? List.of() : extra);
        return merged.stream()
                .filter(t -> granted.contains(t.requiredScope()))
                .filter(t -> !(readonly && t.writeTool()))
                .sorted(java.util.Comparator.comparing(McpToolDefinition::name))
                .toList();
    }
}
