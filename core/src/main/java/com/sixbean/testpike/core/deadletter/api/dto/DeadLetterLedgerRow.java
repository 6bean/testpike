package com.sixbean.testpike.core.deadletter.api.dto;

/**
 * One dead-letter ledger row as shown on the ops page .
 * <p>Carries the payload and the plain-language failure reason for sampling —
 * ISO-8601 UTC strings; the frontend localizes them. x_death_json is intentionally
 * not exposed here: it is broker audit material, not operator reading material.
 */
public record DeadLetterLedgerRow(
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
