package com.sixbean.testpike.core.cases.api.dto;

import java.time.LocalDateTime;

/**
 * This represents a reminder scan candidate row as an internal projection and row mapping for the ReviewRemindService scan SQL.
 * This is not an external contract and must not appear outside scenarios permitted by API layer restrictions, being passed only between internal core services.
 *
 * @param caseId Case ID.
 * @param caseNo test case number used for human-readable notification content.
 * @param title test case title used for notification content.
 * @param projectId project ID used to associate the notification with a project and look up the owner.
 * @param assigneeId reviewer serving as the reminder recipient.
 * @param assignedAt assignment time used as the baseline for comparing two-level thresholds.
 */
public record ReviewRemindCandidate(long caseId, String caseNo, String title, long projectId,
                                    long assigneeId, LocalDateTime assignedAt) {
}
