package com.sixbean.testpike.core.tool.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Result of one registered aggregate query, shaped for the in-dialog live
 * card: column labels in select order + rows of column→value. Every number in
 * here was computed by the database server-side — the model never typesets
 * card data (ruling for P3).
 *
 * @param target the registered target that answered
 * @param columns result column labels (group keys then measure labels)
 * @param rows ordered rows, each keyed by the labels in {@code columns}
 * @param rowLimitUsed the effective row cap applied (target max_rows clamp)
 */
public record QueryResult(
        String target,
        List<String> columns,
        List<Map<String, Object>> rows,
        int rowLimitUsed) {
}
