package com.sixbean.testpike.core.aichat.domain.service;

/**
 * Read-side count of drafts still waiting for a human decision on one object
 * .
 * "Undecided" = pending or stale-with-regenerate-exit; applied and voided
 * drafts are decided and never counted.
 */
public interface DraftPendingCountService {

    /**
 * Count undecided drafts aimed at (objectType, targetId) inside the
 * project segment. A dialog draft resolves its project through the owning
 * session; a system-origin draft anchors project_id directly — both
 * branches land in the same number (V363 column posture).
 */
    int countPending(long projectId, String objectType, long targetId);
}
