package com.sixbean.testpike.core.spec.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecChangeAnalysisInfo;

import java.util.List;

/**
 * Contract change-analysis review queue (W1c F3-twin for API specs).
 * <p>A contract version whose endpoint diff against its previous version is
 * non-empty lands exactly one review-queue row per (old, new) pair — re-analysis
 * of the same pair upserts (fresh change list, preserved human disposition).
 * Disposition is a trace only: no case/request state machine is touched.
 */
public interface ApiSpecChangeAnalysisService {

    /**
     * Analyze a contract version against its previous version and upsert the queue row.
     * Called by the upload/fetch single trigger point inside the same transaction, and
     * available for explicit re-analysis (subscription poll, ops retry).
     *
     * @param operatorId     operator landing the version (queue row created_by)
     * @param newSpecId      tp_api_spec.id of the freshly landed version
     * @param detectedSource upload | fetch | poll — which entry point produced it
     * @throws com.sixbean.testpike.common.exception.ServiceException
     *         no previous version = 400 plain-language rejection (no empty analysis row);
     *         empty endpoint diff = 400 plain-language rejection
     */
    ApiSpecChangeAnalysisInfo analyze(long operatorId, long newSpecId, String detectedSource);

    /**
     * Queue row of a contract version (by new_spec_id).
     *
     * @throws com.sixbean.testpike.common.exception.ServiceException no queue row = 404
     */
    ApiSpecChangeAnalysisInfo get(long newSpecId);

    /**
     * Queue row by primary key (admin-bridge permission lookup: disposition guards
     * read the row's owning project, never a client-supplied projectId).
     *
     * @throws com.sixbean.testpike.common.exception.ServiceException unknown id = 404
     */
    ApiSpecChangeAnalysisInfo getById(long id);

    /**
 * Review-queue page for one project (H1 segment): newest analysis first (id DESC),
 * optional status filter (null = all, 0 = pending, 1 = handled).
 */
    PageResult<ApiSpecChangeAnalysisInfo> list(long projectId, Integer status, int page, int size);

    /**
     * Record the human disposition of one queue row (status 0→1, handled_by/at + note).
     * A trace only — no request/case state machine is touched.
     *
     * @throws com.sixbean.testpike.common.exception.ServiceException
     *         blank note = 400 plain-language (reviewers must state what they did);
     *         unknown id = 404; already handled = 400 naming the original handler
     *         (a second submission must never overwrite the first trace)
     */
    ApiSpecChangeAnalysisInfo handle(long operatorId, long id, String note);

    /**
     * Disposition of several checked rows in one action (queue UI batch trace).
     * All-or-nothing: any unknown or already-handled row rejects the whole call
     * naming it — a silent partial success would leave the reviewer trusting a
     * disposition that never landed.
     */
    List<ApiSpecChangeAnalysisInfo> handleBatch(long operatorId, List<Long> ids, String note);

    /**
     * Pending review rows owned by one project (status = pending). Read-only count
     * for the plan-exit hint slot — the hint surface must not re-implement the
     * queue's status predicate (single source, no second spectrum).
     */
    long countPending(long projectId);
}
