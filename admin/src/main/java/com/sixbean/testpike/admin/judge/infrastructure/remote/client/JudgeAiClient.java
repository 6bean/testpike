package com.sixbean.testpike.admin.judge.infrastructure.remote.client;

import com.sixbean.testpike.admin.judge.api.dto.QaGenEventView;
import com.sixbean.testpike.admin.judge.api.dto.QaGenJobView;
import com.sixbean.testpike.admin.judge.api.dto.QaGenRunView;
import com.sixbean.testpike.admin.judge.api.dto.QaGenSubmitView;

import java.util.List;

/**
 * <p>Job plane : the submit door answers either an inline preview
 * (within the sync group cap) or an accepted receipt; the two read faces then
 * stream the run's progress so an over-cap document never hangs the caller.
 */
public interface JudgeAiClient {

    /**
     * Submit the question-generation run (admin→ai-service POST
     * /internal/judge/evalset/qa-gen-submit). Within the sync cap the receipt
     * carries the full preview inline; beyond it, only a run token — chunking,
     * cost gates and parsing stay single-source in ai-service, the bridge
     * never re-decides.
     */
    QaGenSubmitView submit(Long projectId, Long documentId, String modelHint, Long operatorId);

    /** Latest job row of the document (status + cumulative group/item face). */
    QaGenJobView jobStatus(Long documentId, Long operatorId);

    /**
     * Cursor window of the run's process events, replay-idempotent by row id.
     * limit=null defers to the ai-service hot key (no second default here).
     */
    List<QaGenEventView> jobEvents(Long documentId, String runToken, long cursor,
                                   Integer limit, Long operatorId);

    /**
     * Run history of the document's question-generation door, newest first
     * (resident revisit entry of the tail window). The terminal verdict is
     * resolved read-side in ai-service — the bridge never re-decides.
     * limit=null defers to the ai-service hot key (no second default here).
     */
    List<QaGenRunView> qaGenRuns(Long documentId, Integer limit, Long operatorId);
}
