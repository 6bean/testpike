package com.sixbean.testpike.core.deadletter.api.dto;

import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterFailureClass;

/**
 * One dead-lettered message booked into the ledger .
 * <p>Input DTO of {@code DeadLetterLedgerService.record}: the DLQ listeners capture
 * the queue/exchange/routing-key triple, the raw payload, the broker's x-death header and
 * the already-classified failure (single-source classifier, never re-derived here).
 *
 * @param messageFingerprint broker message id when present, else a queue+payload digest;
 * unique together with queueName — the idempotency root
 * @param queueName the DLQ the message was dead-lettered into (not null/blank)
 * @param origExchange exchange the message was published to (nullable for unroutable publishes)
 * @param origRoutingKey routing key of the original publish (nullable)
 * @param payloadJson raw message body as text, masked upstream (not null)
 * @param failureClass classification from DeadLetterFailureClassifier (never UNKNOWN-by-omission:
 * UNKNOWN is the honest fallback the classifier itself produces)
 * @param failureReason plain-language cause-chain summary, masked (nullable when the broker
 * dead-lettered without a listener failure)
 * @param xDeathJson x-death header captured as JSON (nullable, kept for audit)
 */
public record DeadLetterLedgerInput(
        String messageFingerprint,
        String queueName,
        String origExchange,
        String origRoutingKey,
        String payloadJson,
        DeadLetterFailureClass failureClass,
        String failureReason,
        String xDeathJson) {
}
