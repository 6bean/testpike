package com.sixbean.testpike.core.cases.api.dto;

import java.time.LocalDateTime;

/**
 * The Recycle Bin row is in the GET /cases/recycle-bin response.
 * <p>deletedBy/deletedAt are obtained by reverse lookup of the latest audit record with action=delete (tp_test_case has no deleted_by column).
 * The audit trail is the only authoritative source — the Recycle Bin decision uses zero new tables; existing directly created soft-deleted rows (without audit) have both columns null.
 *
 * @param id is the test case ID.
 * @param caseNo is the test case number (retained when moved to the Recycle Bin and not renumbered).
 * @param projectId is the project ID.
 * @param title is the test target.
 * @param priority      0=P0 1=P1 2=P2
 * @param status is the status before soft deletion (the original status is restored after restore, 9 states).
 * @param version is the current version number (carried by the expectedVersion for restore optimistic locking).
 * @param deletedBy is the deleted-by user ID (latest operator in audit delete records; null if no audit).
 * @param deletedByName is the deleted-by user display name (display_name falls back to username).
 * @param deletedAt is the deletion time (audit delete time).
 */
public record CaseRecycleInfo(long id, String caseNo, long projectId, String title, int priority,
                              int status, int version, Long deletedBy, String deletedByName,
                              LocalDateTime deletedAt) {
}
