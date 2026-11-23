package com.sixbean.testpike.admin.deadletter.api.dto;

/**
 * Replay outcome mirror for the ops page .
 * <p>Verbatim shape of the core receipt: the resolved repost target (original
 * exchange or the default exchange, original routing key or the queue-name
 * fallback) plus the new round count, so the page can confirm where the message
 * went back to without reading broker state. admin adds no judgement here.
 */
public record DeadLetterReplayReceiptView(
        long ledgerId,
        String messageFingerprint,
        String deadLetterQueueName,
        String repostedExchange,
        String repostedRoutingKey,
        int replayRound) {
}
