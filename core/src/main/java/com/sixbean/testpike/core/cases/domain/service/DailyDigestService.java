package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.cases.api.dto.DailyDigestResult;

/**
 * The daily digest summary service implements the DAILY_DIGEST capability.
 * <p>At 08:00, the service scans the previous day's four-element set for active projects: new cases, execution results, new defects and unclosed defects, and pending review backlog.
 * Coverage change is defined as new coverage association, and the notification is pushed to the union of project owner and project creator with LinkedHashSet deduplication.
 * If the owner equals the creator, only one record is sent; projects with no previous-day activity are skipped and no empty reports are sent.
 * <p>Idempotency is enforced by dedup_key=daily-digest:{projectId}:{calendar_day}:{recipient}, including the recipient to prevent cross-recipient deduplication.
 * Same-day reruns and failed retries in the next round do not produce duplicates.
 * <p>Preference filtering uses DAILY_DIGEST=default_off from the dictionary at the single point NotificationService.create.
 * This service only calls create and does not recheck, and a create return value of 0 means filtered because default_off has no subscription record or is unsubscribed.
 *
 */
public interface DailyDigestService {

    /** Full-project daily digest scan is triggered by the 08:00 Scheduler and explicitly driven by the core internal endpoint with the same criteria. */
    DailyDigestResult scan();
}
