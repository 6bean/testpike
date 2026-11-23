package com.sixbean.testpike.core.deadletter.domain.service;

import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterFailureClass;

/**
 * <p>Single egress for every "replay / void" operational action: the ops page and the
 * auto-replay loop must both consume these methods — no second replay gate may
 * exist anywhere else (R40; the failure-class vocabulary stays owned by
 * {@link DeadLetterFailureClass}, the status vocabulary by DeadLetterLedgerStatus).
 * row younger than the 24h consumer-side guard window is deduped downstream and is safe;
 * a replay of an older row is executed as a brand-new message, so it requires an
 * explicit re-execution confirmation flag, and the row must have been written with its
 * fingerprint as the dedup snapshot the DB-side second-layer check reads.
 */
public interface DeadLetterReplayService {

    /**
     * Replay one ledger row back to its original exchange/routing key.
     * <p>Guards, in order: row must exist and still be pending; ENTITY_GONE rows are
     * hard-rejected regardless of caller (O3-a-2 — a replay would dead-letter again and
     * only burn retry quota); a row older than the 24h idempotent window is rejected
     * unless {@code confirmReexecute} is set; the pending-to-replayed transition is a
     * single atomic UPDATE (DB-side second-layer dedup: whoever flips the row first
     * owns the only repost).
     *
     * @param ledgerId         tp_mq_dead_letter row id
     * @param confirmReexecute acknowledge re-execution semantics for rows older than 24h
     * @param operator         acting user, recorded on the row
     * @return receipt describing where the message was reposted and the new round count
     */
    com.sixbean.testpike.core.deadletter.api.dto.DeadLetterReplayReceipt replayOne(
            long ledgerId, boolean confirmReexecute, String operator);

    /**
     * Replay all pending rows of one DLQ filtered by failure class (the "batch by class"
     * action). Rows outside the confirmation scope (older than 24h while
     * {@code confirmReexecute} is false) stay pending. {@link DeadLetterFailureClass#ENTITY_GONE}
     * is rejected outright — it is never a replayable class.
     *
     * @return number of rows successfully reposted
     */
    int replayByFailureClass(String deadLetterQueueName, DeadLetterFailureClass failureClass,
                             boolean confirmReexecute, String operator);

    /**
     * Replay every replayable pending row of one DLQ (the "whole queue" action):
     * ENTITY_GONE rows are skipped, over-24h rows require the confirmation flag.
     *
     * @return number of rows successfully reposted
     */
    int replayQueue(String deadLetterQueueName, boolean confirmReexecute, String operator);

    /**
 * status=voided + void_reason + operator, same posture as the duplicate_drop audit —
 * nothing is ever physically deleted). Rows already replayed or voided are rejected.
 *
 * @return true when this call flipped the row (a repeat void is rejected, not silent)
 */
    boolean voidLedger(long ledgerId, String reason, String operator);
}
