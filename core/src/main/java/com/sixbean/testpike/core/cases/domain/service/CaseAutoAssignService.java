package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.cases.api.dto.AutoAssignRequest;
import com.sixbean.testpike.core.cases.api.dto.AutoAssignResultView;

/**
 * Test case auto-assignment service (M8 C4, PRD 16).
 * <p>Capability: deterministically dispatch unassigned pending-review test cases to project testers by load balancing, with the candidate pool defined by the K6 same-source predicate.
 * The predicate is tester/owner and not developer, the load is the under-review count under the same definition as workbench cards, and the sort key is P0, then P2, then creation order, then id.
 * Virtual load is evenly distributed by rolling greedy assignment, and the per-row conditional update on assignee_id IS NULL is naturally idempotent, with only one concurrent winner.
 * &gt;500 uses batched transactions; preview mode performs zero writes, and formal mode reuses the existing BATCH_ASSIGNED notification chain.
 */
public interface CaseAutoAssignService {

    /**
     * Automatic assignment returns a dispatch plan when preview=true, and performs formal dispatch plus notification when preview=false.
     *
     * @param operatorId operator, with the assignment eligibility dual gate on the admin side and core focused on data correctness.
     * @param request projectId + caseIds (nullable, meaning all unassigned pending-review test cases in the project) + preview.
 * @return the dispatched count and per-assignee list, where preview and actual dispatch have the same structure and are comparable.
     */
    AutoAssignResultView autoAssign(Long operatorId, AutoAssignRequest request);
}
