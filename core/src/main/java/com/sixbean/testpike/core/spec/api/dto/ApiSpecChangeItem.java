package com.sixbean.testpike.core.spec.api.dto;

import java.util.List;

/**
 * One endpoint-level change row of a contract version diff with its closed impact
 * lists. Only keys are persisted in the queue JSONB (no snapshot text), so a later
 * contract restyle cannot rot the ledger.
 * <p>Impact closure is an equality join on the folded contract key: imported requests
 * by {@code tp_apitest_request.contract_endpoint_key}, promoted cases via
 * {@code promoted_case_id} (soft-deleted excluded), mock stubs from the openapi-sourced
 * derived-key lookup. A change matching nothing keeps its fact row with empty lists —
 * the queue never pretends a closure it did not find.
 *
 * @param endpointKey  contract endpoint key as produced by the version diff
 *                     (upper METHOD + normalized path, template segments kept)
 * @param changeType   add | changed | removed
 * @param requestIds   imported API requests sitting on the folded key
 * @param caseIds      promoted cases of those requests (not deleted)
 * @param mockStubIds  contract-derived mock stubs sharing the folded key
 */
public record ApiSpecChangeItem(String endpointKey, String changeType,
                                List<Long> requestIds, List<Long> caseIds,
                                List<Long> mockStubIds) {

    public ApiSpecChangeItem {
        requestIds = requestIds == null ? List.of() : List.copyOf(requestIds);
        caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
        mockStubIds = mockStubIds == null ? List.of() : List.copyOf(mockStubIds);
    }
}
