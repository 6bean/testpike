package com.sixbean.testpike.core.deadletter.domain.component;

/**
 * One decoded dead-letter message ready for ledger booking .
 * <p>Value object produced by {@code DeadLetterMessageDecoder} from a delivered DLQ
 * message; the egress listener turns it into a {@code DeadLetterLedgerInput} after
 * classifying the failure. Kept separate from the booking DTO so decoding has its own
 * test surface and the raw broker header types never escape the decoder.
 *
 * @param messageFingerprint idempotency root: broker message id when usable, else digest
 * @param origExchange exchange of the original publish read from the newest x-death entry
 * @param origRoutingKey routing key of the original publish (first entry of the newest x-death)
 * @param payloadText raw body decoded as UTF-8 text
 * @param xDeathJson the whole x-death header list serialized as JSON ("" when absent)
 * @param xDeathReason reason of the newest x-death entry (rejected/expired/...), null when absent
 * @param failureText captured listener failure text from the recoverer header
 * , null when the message carries none
 */
public record DeadLetterSignal(
        String messageFingerprint,
        String origExchange,
        String origRoutingKey,
        String payloadText,
        String xDeathJson,
        String xDeathReason,
        String failureText) {
}
