package com.sixbean.testpike.aiservice.gen.infrastructure.remote;

import java.util.List;
import java.util.Map;

/**
 * Aggregate-query result remote mirror (AO batch P3 ; contract
 * mirror of core {@code QueryResult} — JSON field names aligned one to one,
 * ai-service must not depend on the core module, ToolInvokeResultRemote same
 * mirror baseline).
 * <p>The live card renders columns/rows verbatim; every number here was
 * computed by the database server-side.
 *
 * @param target registry key that answered
 * @param columns result column labels (group keys then measure labels)
 * @param rows ordered rows keyed by the labels in {@code columns}
 * @param rowLimitUsed effective row cap applied by the server
 */
public record QueryResultRemote(
        String target,
        List<String> columns,
        List<Map<String, Object>> rows,
        int rowLimitUsed) {
}
