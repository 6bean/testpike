package com.sixbean.testpike.core.cases.api.dto;

/**
 * One open flaky-probe session joined with the case context the sweep needs
 * . Read model only: the ledger row stays the source of truth,
 * this carries the fields required to fold results and address the notification.
 *
 * @param id tp_flaky_probe.id
 * @param caseId tp_test_case.id under quarantine
 * @param projectId case project (notification scoping)
 * @param greenStreak consecutive greens already recorded on the session
 * @param lastTaskId tp_exec_task.id cursor already folded into the streak
 * @param state 0 = probing, 1 = restore proposal pending human confirmation
 * @param assigneeId case assignee (notification recipient; null = ledger-only proposal)
 * @param caseTitle display title for the notification body
 */
public record FlakyProbeSession(long id, long caseId, long projectId, int greenStreak,
                                long lastTaskId, int state, Long assigneeId, String caseTitle) {
}
