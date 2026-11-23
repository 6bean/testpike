package com.sixbean.testpike.core.apitest.domain.service;

import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetDetail;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetPageView;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetPreviewStep;
import com.sixbean.testpike.core.apitest.api.dto.RunSetCreateInput;

import java.util.List;

/**
 * Whole-collection run ledger service (; scope=collection over the existing
 * per-request tp_apitest_run rows).
 * <p>Pinning split (design W-c2): request definitions + environment are frozen
 * at launch into pinned_json; the variable pool keeps evolving through extract
 * writes — freezing the pool would break chain propagation itself.
 * <p>Child-row dispatch/advance orchestration lands in the next segment; this
 * service owns the ledger, the snapshot and the shared guards.
 */
public interface ApiRunSetService {

    /**
     * Open a collection run: validate mode + env whitelist, freeze the request
     * definitions in tree order, snapshot the pool, insert child run rows
     * (status 0=pending, run_set_id/seq_no set).
     */
    ApiRunSetInfo create(Long operatorId, RunSetCreateInput input);

    /** Ledger detail (404 when missing). */
    ApiRunSetInfo get(long runSetId);

    /**
 * Detail read-model : ledger + every child run joined to its
 * request, ordered by seq_no. 404 when the ledger is missing.
 */
    ApiRunSetDetail detail(long runSetId);

    /**
 * Launch-preview read-model : one row per request in tree
 * order with extracted keys, raw {{VAR:...}} references and dependency
 * arrows (key + producing request + ordering honesty flag). Computed from
 * the same plan grammar the advancer uses — never re-derived in the UI.
 */
    List<ApiRunSetPreviewStep> preview(long collectionId);

    /**
     * One paged window of the collection's run-set history, newest first
     * (display-scale iron law — the management-plane list door carries
     * page/pageSize; the clamp dialect lives in the shared PageWindow).
     */
    ApiRunSetPageView listByCollection(long collectionId, int page, int pageSize);

    /** Counts of child runs still pending inside a set (advance guard helper). */
    List<Long> childRunIds(long runSetId);
}
