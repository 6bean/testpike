package com.sixbean.testpike.core.linkage.domain.service;

import java.util.List;

/**
 * Bean-side edge detector of the change-linkage bus (detector kind 1).
 *
 * <p>An edge row in tp_change_linkage is data: most fans are a plain SQL
 * predicate (kind 2) the dispatcher can run verbatim. A few fans need the
 * Java match-key rules as the single judgment source — the folded endpoint
 * key comparison lives in ContractMatchKeyRules and must never be
 * re-implemented as SQL text, or the two sides drift apart on the first rule
 * tweak. Those edges name a Spring bean implementing this interface instead.
 *
 * <p>Implementations live in the linkage domain components and only READ
 * downstream rows: the dispatcher owns every tp_linkage_impact write, so a
 * detector can never mint or mutate impacts on its own.
 */
public interface LinkageEdgeDetector {

    /**
     * Resolve the downstream objects referenced by one upstream object.
     *
     * @param upstreamObjectId id of the changed object row (edge's upstream
     *                         member, e.g. a tp_api_spec version row id)
     * @return downstream ids to arm on this edge; empty means no fan-out
     */
    List<Long> detectDownstream(Long upstreamObjectId);
}
