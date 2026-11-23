package com.sixbean.testpike.core.webelement.domain.service;

/**
 * Draft converter over the per-page network write-risk ledger :
 * turns ONE booked failed request into a pending case-write draft card, so a
 * reviewer can carry a probe-discovered interface failure into the test-case
 * domain without hand-copying URLs. The converter only FILES the proposal —
 * the generic confirmation door (project membership + permission + one-click
 * human accept) still decides whether a case is ever written, keeping the
 * write-path red line intact.
 */
public interface PageWriteFailureDraftService {

    /**
 * Convert one failure triple of one risk row into a CASE_WRITE draft.
 *
 * @param projectId the caller's project segment (strict, fail-closed)
 * @param profileId the risk ledger row id
 * @param failureIndex 0-based index into the row's net-failures window
 * @return the draft ledger row id, confirmable through the generic door
 */
    long toCaseDraft(Long projectId, long profileId, int failureIndex);
}
