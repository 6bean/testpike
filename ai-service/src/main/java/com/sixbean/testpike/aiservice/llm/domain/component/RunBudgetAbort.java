package com.sixbean.testpike.aiservice.llm.domain.component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * measure 1 — a single cooperative-cancel signal shared by every task
 * of one budgeted run (extraction group fan-outs and their deferred rounds).
 *
 * <p>Why this exists: the budget gate used to only flip the ledger verdict
 * (INCOMPLETE / honest AI_TIMEOUT at the terminal face) while workers kept
 * launching provider calls past the gate — goldfinal3 burned 35 groups for
 * 142 minutes against a 7200s window. One signal object per run lets the
 * first consumer that observes a spent window stop every sibling: the
 * fan-out checks it before scheduling each task and while waiting, and each
 * task checks it before launching any new provider attempt.
 *
 * <p>Semantics (asserted in {@code GroupParallelFanoutTest}):
 * <ul>
 * <li>idempotent trip — the first reason wins, later trips are no-ops;</li>
 * <li>plain visibility — {@link #aborted} is a volatile read, safe from
 * any worker thread, no locks;</li>
 * <li>cooperative only — callers stop at their next checkpoint; threads
 * blocked in transport are released by the fan-out's cancel path.</li>
 * </ul>
 *
 * <p>The signal carries no domain vocabulary: budget owners (for example
 * {@code ExtractDeadline.spent in the doc face}) decide WHEN to trip it,
 * so the same component serves the spec and judge fan-outs in the follow-up
 * slices without borrowing ai.doc.* keys .
 */
public final class RunBudgetAbort {

    private final AtomicBoolean tripped = new AtomicBoolean(false);

    /** Reason captured by the first trip, for upstream attribution. */
    private volatile String reason;

    /** Trip the signal once; later trips keep the original reason.
     *  @return true only for the winning trip */
    public boolean trip(String tripReason) {
        if (tripped.compareAndSet(false, true)) {
            reason = tripReason;
            return true;
        }
        return false;
    }

    /** Whether the run has been aborted (volatile read, checkpoint cheap). */
    public boolean aborted() {
        return tripped.get();
    }

    /** First trip's reason, null while the signal is untouched. */
    public String reason() {
        return reason;
    }
}
