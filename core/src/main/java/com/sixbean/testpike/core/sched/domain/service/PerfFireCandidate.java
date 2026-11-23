package com.sixbean.testpike.core.sched.domain.service;

/**
 * Scheduled-fire candidate row (perf scenario family): a scenario with the
 * periodic switch on and a cron expression pinned. The scanner launches it at
 * each theoretical trigger point through the single shared launch entry, so the
 * scheduled door inherits every safety decision the manual and CI doors make.
 *
 * @param id            scenario id
 * @param projectId     owning project (ledger isolation + launch project scoping)
 * @param name          scenario name (human-readable firing message subject)
 * @param cronExpr      Spring 6-segment cron (validated on the save side)
 * @param scheduleEnvId pinned launch-time environment override (null = scenario default)
 */
public record PerfFireCandidate(long id, long projectId, String name,
                                String cronExpr, Long scheduleEnvId) {
}
