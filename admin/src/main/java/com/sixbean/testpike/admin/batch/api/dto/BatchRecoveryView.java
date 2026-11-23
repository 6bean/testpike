package com.sixbean.testpike.admin.batch.api.dto;

/**
 *
 * @param sweepReclaimedCount tasks the automatic offline sweep reclaimed earlier that are
 * still queued (sweep-vs-manual reconciliation; makes recoveredCount=0 auditable)
 */
public record BatchRecoveryView(long batchId, int batchStatus, String batchStatusLabel,
                                int recoveredCount, int sweepReclaimedCount,
                                int runningCount, int terminalCount,
                                int pendingCount, String message) {
}
