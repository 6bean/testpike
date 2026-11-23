package com.sixbean.testpike.core.batch.api.dto;

/**
 * Batch crash recovery reconciliation view.
 * <p>Result snapshot after the single recover entry completes its three stages (recovering offline running tasks in this batch, supplementing all terminal states, and reconciliation counts).
 * The four counts use task state as the source of truth, with recoveredCount representing this recovery (running tasks owned by an already offline executor transition from 2 to 1 and requeue).
 * runningCount is running tasks whose executor is online (the domain must not move them while waiting for their actual callback), and terminalCount is retained already terminal tasks.
 * The retained terminal statuses are 3/4/5/6/7 and are not redispatched, while pendingCount is queued tasks waiting to be dispatched (status 1, including recovered tasks).
 * message is a human-readable reconciliation note displayed directly in the frontend recovery report dialog, and JSON dumps are forbidden.
 *
 * @param batchId batch ID.
 * @param batchStatus batch status code after the recovery action, possibly advanced to 6/7.
 * @param batchStatusLabel Chinese name of the batch status, displayed from the same source as BatchInfo.statusLabel.
 * @param recoveredCount number of tasks recovered in this run, where repeated recovery is 0 and conditional update is naturally idempotent.
 * @param sweepReclaimedCount tasks the automatic offline sweep reclaimed earlier that are
 *    still queued (reconciles the 15s-sweep vs manual-recover race: recoveredCount=0 with
 *    a non-zero value here means the sweep won, never a silent "nothing to reclaim")
 * @param runningCount number of running tasks whose executor is online and are unaffected.
 * @param terminalCount number of already terminal tasks with retained results.
 * @param pendingCount number of queued tasks waiting to be dispatched.
 * @param message human-readable reconciliation note.
 */
public record BatchRecoveryView(long batchId, int batchStatus, String batchStatusLabel,
                                int recoveredCount, int sweepReclaimedCount,
                                int runningCount, int terminalCount,
                                int pendingCount, String message) {
}
