package com.sixbean.testpike.core.tool.api.dto;

import java.util.List;

/**
 * The controlled query DSL of the declarative read-only proxy (AO batch
 * P3, closing the QuerySpec debt first named under ; ruling: "the model
 * only composes intent, the server computes the numbers").
 * <p>A QuerySpec names a registered read-only target and reshapes it with
 * filters / groupBy / aggregations / orderBy / limit. It carries no write
 * primitives, no joins and no cross-project reach — the shape itself is the
 * contract. The interpreter re-reads the raw JSON (see
 * {@code QuerySpecServiceImpl.refuseUnknownKeys}) so keys smuggled under any
 * other name are refused fail-closed instead of silently dropped.
 *
 * @param target tp_query_target.target_key of the registered view
 * @param filters equality conditions, ANDed; field must be a registered
 * dimension column
 * @param groupBy registered dimension columns to aggregate over
 * @param aggregations server-computed measures (count / sum / avg / min / max)
 * @param orderBy "column[:asc|:desc]" over a result column
 * @param limit row cap; clamped to the target's max_rows
 */
public record QuerySpec(
        String target,
        List<QueryFilter> filters,
        List<String> groupBy,
        List<QueryAggregation> aggregations,
        List<String> orderBy,
        Integer limit) {
}
