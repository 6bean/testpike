package com.sixbean.testpike.core.tool.domain.service;

import com.sixbean.testpike.core.tool.api.dto.QueryResult;
import com.sixbean.testpike.core.tool.api.dto.QuerySpec;

/**
 * Declarative read-only aggregate query proxy . The dialog
 * answers "how many / how distributed" with server-computed numbers over
 * registered targets; the model only composes intent.
 */
public interface QuerySpecService {

    /**
 * Compile and run one QuerySpec against the registered target.
 *
 * @param spec parsed DSL (never raw SQL — there is no raw-SQL door)
 * @param projectId the segment the query must stay inside (server-side fact,
 * never model-supplied)
 * @param actorId the calling user (visibility re-check at the execution
 * point, same single gate as tool invoke)
 */
    QueryResult run(QuerySpec spec, long projectId, Long actorId);

    /**
     * Run from the raw JSON the model produced. The interpreter re-reads the
     * raw object so keys outside the DSL shape (a smuggled SQL fragment, a
     * join clause under another name) are refused fail-closed instead of
     * dropped silently by the record binding.
     */
    QueryResult runJson(String rawJson, long projectId, Long actorId);

    /**
     * Model-facing vocabulary of callable targets (registry keys + allow-list
     * columns, physical detail withheld) — the dialog tool embeds it in the
     * tool description so the model composes specs against real keys.
     */
    java.util.List<com.sixbean.testpike.core.tool.api.dto.QueryTargetView> callableTargets();
}
