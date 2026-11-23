package com.sixbean.testpike.core.tool.api.dto;

/**
 * Model-facing view of one registered query target (tp_query_target). It
 * names the callable key and the allow-listed columns — enough for the
 * dialog to compose a spec that passes the interpreter — and deliberately
 * omits the physical base table, the scope column and the hard filter (those
 * are server-side enforcement detail; exposing them would teach the model
 * where to aim and gain nothing).
 *
 * @param targetKey        registry key the spec must name
 * @param dimensionColumns comma-joined allow-list for filters / groupBy
 * @param measureColumns   comma-joined allow-list for sum/avg/min/max
 * @param maxRows          row cap the server clamps the limit to
 */
public record QueryTargetView(
        String targetKey,
        String dimensionColumns,
        String measureColumns,
        int maxRows) {
}
