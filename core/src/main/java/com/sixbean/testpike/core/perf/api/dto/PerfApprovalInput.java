package com.sixbean.testpike.core.perf.api.dto;

/**
 *
 * @param scenarioId Approval-linked perf scenario (D8): when the auto-launch switch
 * perf.approval.auto-launch.enabled is on, approving this ticket
 * fires the linked scenario through the single launch channel.
 * null = plain approval, approving never launches anything.
 * Validated fail-closed at apply time (must belong to the
 * filing project).
 */
public record PerfApprovalInput(Long envId, Integer requestedConcurrency,
                                String isolationMode, String reason, Long scenarioId) {

    /** Pre-D8 call-site form: no scenario link (approval only unlocks the window). */
    public PerfApprovalInput(Long envId, Integer requestedConcurrency,
                             String isolationMode, String reason) {
        this(envId, requestedConcurrency, isolationMode, reason, null);
    }
}
