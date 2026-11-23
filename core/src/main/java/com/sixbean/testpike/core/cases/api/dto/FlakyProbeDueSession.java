package com.sixbean.testpike.core.cases.api.dto;

/**
 * An open flaky re-probe session whose next_probe_at has come due (AO batch
 * dispatch stage). Carries the minimal identity the dispatcher needs to hand
 * one re-probe run to the shared execution channel: session row, case and owning
 * project (project scope drives the environment resolution inside
 * ExecTaskService.create's production guard).
 *
 * @param sessionId tp_flaky_probe row id
 * @param caseId quarantined case to re-probe
 * @param projectId owning project (environment and guard scope)
 */
public record FlakyProbeDueSession(long sessionId, long caseId, long projectId) {
}
