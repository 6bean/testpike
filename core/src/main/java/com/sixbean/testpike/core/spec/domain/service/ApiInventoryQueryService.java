package com.sixbean.testpike.core.spec.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.spec.api.dto.InventoryCoverageSummary;
import com.sixbean.testpike.core.spec.api.dto.InventoryEndpointView;
import com.sixbean.testpike.core.spec.api.dto.InventoryQuery;

/**
 * API inventory query of the contract inventory page): the read side
 * that answers "which contract endpoints exist, which assets hang off each one,
 * what share is covered" against the latest version of a contract name chain.
 * <p>Judgment sources stay single: anchor = max(spec_version) row (no published
 * column exists on tp_api_spec — C-1), folded match key = ContractMatchKeyRules,
 * freshness = structure-hash comparison with the previous row, coverage
 * denominator excludes deprecated endpoints (R-9), mock provenance note is
 * derived at read time from the hit ledger (R-8).
 */
public interface ApiInventoryQueryService {

    /**
     * Paged endpoint rows of the anchored contract, with asset counts.
     * Unknown contract name in the project = empty page (never an error —
     * the page shell still renders).
     */
    PageResult<InventoryEndpointView> queryEndpoints(InventoryQuery query);

    /** Coverage summary card for the same anchor (denominator = non-deprecated). */
    InventoryCoverageSummary queryCoverage(long projectId, String specName);

    /**
     * Project-wide coverage across EVERY contract name chain: each chain is
     * measured on its own anchor version with the same denominator rule
     * (non-deprecated rows only), then summed. Used by the plan-exit coverage
     * gate so the gate and the inventory card never hold two coverage specs.
     * specName/specVersion of the returned summary carry no meaning here
     * (null/0) — only total, endpointsWithRequests and coveragePercent do.
     */
    InventoryCoverageSummary projectCoverageSummary(long projectId);
}
