package com.sixbean.testpike.admin.deadletter.api.dto;

/**
 * Mirror of one dead-letter ledger row for the ops page .
 * <p>Carries payload plus the plain-language reason — raw stack traces and broker
 * x_death material stay inside core on purpose. Timestamps are ISO-8601 UTC strings;
 * the frontend localizes them.
 */
public record DeadLetterLedgerRowView(
        long id,
        String messageFingerprint,
        String queueName,
        String origExchange,
        String origRoutingKey,
        String payloadJson,
        String failureClass,
        String failureReason,
        int replayRound,
        String status,
        String voidReason,
        String operator,
        String createdAtUtc,
        String updatedAtUtc) {
}
