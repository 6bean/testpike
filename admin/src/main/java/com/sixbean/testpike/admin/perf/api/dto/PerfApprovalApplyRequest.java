package com.sixbean.testpike.admin.perf.api.dto;

/**
 *
 * @param scenarioId D8 approval-linked scenario (null = plain approval; the
 * auto-launch switch decides whether approving fires it —
 * validated at the core write point, this bridge only forwards)
 */
public record PerfApprovalApplyRequest(Long projectId, Long envId, Integer requestedConcurrency,
                                       String isolationMode, String reason, Long scenarioId) {

    /** Pre-D8 call-site form: no scenario link. */
    public PerfApprovalApplyRequest(Long projectId, Long envId, Integer requestedConcurrency,
                                    String isolationMode, String reason) {
        this(projectId, envId, requestedConcurrency, isolationMode, reason, null);
    }
}
