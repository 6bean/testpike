package com.sixbean.testpike.core.deadletter.api.dto;

/**
 * Outcome of one successful replay of a dead-letter ledger row .
 * <p>The receipt mirrors what the ops page must show after a replay: the resolved
 * repost target (original exchange, or the empty default exchange; original routing
 * key, or the queue-name fallback) and the new round count, so operators can verify
 * the message went back where it came from without reading broker state.
 */
public record DeadLetterReplayReceipt(
        long ledgerId,
        String messageFingerprint,
        String deadLetterQueueName,
        String repostedExchange,
        String repostedRoutingKey,
        int replayRound) {
}
