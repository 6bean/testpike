package com.sixbean.testpike.core.deadletter.tools;

import java.util.List;

/**
 * Per-queue reconciliation row of the DLQ stock migration .
 * number of messages peeked equals the depth snapshot taken before peeking
 * (diff zero), every peeked message ended in a booking outcome (new or
 * duplicate — never a silent gap), and no booking failed. The depth after the
 * run is recorded for the audit report: un-acked peeks return to the queue, so
 * it must match the snapshot on a quiet broker.
 *
 * @param queueName dead-letter queue this row describes
 * @param snapshotCount queue depth before peeking (queue.declare-passive)
 * @param peekedCount messages read through basic.get without acknowledging
 * @param bookedNew ledger rows this run inserted
 * @param bookedDuplicate messages already in the ledger (idempotent re-booking)
 * @param bookingFailures peeks whose booking threw (their rows stay unbooked)
 * @param failureSamples up to five exception texts from failed bookings, so the
 * red gate is diagnosable from the report alone
 * @param depthAfter queue depth after the run (stock must be intact)
 */
public record DeadLetterStockTally(
        String queueName,
        int snapshotCount,
        int peekedCount,
        int bookedNew,
        int bookedDuplicate,
        int bookingFailures,
        List<String> failureSamples,
        int depthAfter) {

    /** Peeked minus snapshot: nonzero means the stock moved while we measured. */
    public int diff() {
        return peekedCount - snapshotCount;
    }

    /** The hard-gate verdict for this queue. */
    public boolean isCleared() {
        return diff() == 0 && bookingFailures == 0 && bookedNew + bookedDuplicate == peekedCount;
    }
}
