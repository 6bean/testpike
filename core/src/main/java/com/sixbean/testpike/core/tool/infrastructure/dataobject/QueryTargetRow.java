package com.sixbean.testpike.core.tool.infrastructure.dataobject;

/**
 * One row of the read-only query-target allow list (tp_query_target, V333).
 * Plain data carrier between the registry loader and the QuerySpec compiler;
 * the hard_filter text is registry content and never model-supplied.
 */
public record QueryTargetRow(
        String targetKey,
        String baseTable,
        String scopeColumn,
        String dimensionColumns,
        String measureColumns,
        String hardFilter,
        int maxRows) {
}
