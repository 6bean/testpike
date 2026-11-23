package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.cases.api.dto.ReviewRemindResult;

/**
 * Review reminder service (Q4, PRD).
 * <p>Hourly scan of assigned cases pending review (status 1/2): if assigned longer than review.remind-hours (default 24h).
 * Remind reviewers (REVIEW_REMIND, idempotent deduplication per user, case, and calendar day); if review.timeout-hours (default 72h) is exceeded.
 * Escalate to the project owner (REVIEW_TIMEOUT, mandatory subscription level, alongside LOGIN_LOCKED—07).
 * Setting the threshold to 0 disables that level.
 * <p>Notifications reuse the existing in-app message pipeline (NotificationService, event 07, do not duplicate).
 * Cases with empty assigned_at, unassigned, or processed (status ≥ 3) are excluded (inverse semantics).
 *
 */
public interface ReviewRemindService {

    /** Run one reminder scan (hourly Scheduler or internal endpoint L4 driven with the same criteria). */
    ReviewRemindResult scan();
}
