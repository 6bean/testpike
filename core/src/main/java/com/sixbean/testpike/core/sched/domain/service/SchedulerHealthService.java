package com.sixbean.testpike.core.sched.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.sched.api.dto.SchedulerHealthInfo;


public interface SchedulerHealthService {

    /**
     * Historical three-argument form: keeps the catalog insertion order with
     * zero behavioural difference for callers that do not ask for a sort
     * (keyword=task name/plain-word filter, page defaults to 1, size capped
     * at 200).
     */
    default PageResult<SchedulerHealthInfo> health(String keyword, int page, int size) {
        return health(keyword, page, size, null, null);
    }

    /**
     * Paged health read with server-side ordering.
     *
     * @param sortBy  whitelisted sort key (job_name/description/locked_by/
     *                locked_at/lock_until); null or blank keeps the catalog
     *                insertion order, an off-list key is refused fail-closed
     * @param sortDir asc for ascending, anything else desc (same posture as
     *                the trigger-ledger whitelist)
     */
    PageResult<SchedulerHealthInfo> health(String keyword, int page, int size,
                                           String sortBy, String sortDir);
}
