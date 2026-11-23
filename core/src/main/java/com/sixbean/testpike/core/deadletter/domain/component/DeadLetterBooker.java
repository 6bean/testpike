package com.sixbean.testpike.core.deadletter.domain.component;

import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterLedgerInput;
import com.sixbean.testpike.core.deadletter.domain.service.DeadLetterLedgerService;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Single-source decode-classify-book path for dead-lettered messages
 * (AJ batch , R40: the live listener and the stock-migration tool
 * must derive ledger rows by exactly one code path).
 * <p>Fingerprint identity is the hard requirement: the stock tool pre-books
 * today's DLQ backlog and the listeners book tomorrow's arrivals — if the two
 * derivations ever disagreed, the same message would produce two ledger rows
 * and the reconciliation gate would be meaningless. That is why nothing here
 * re-implements the decoder or classifier and why the listener now delegates.
 * <p>The booking result is returned verbatim: an {@code Optional.empty} is an
 * idempotent duplicate (already booked), and any booking exception propagates
 * untouched so each caller keeps its own fate decision (listener nacks with
 * requeue, the migration tool counts the queue red).
 */
@Component
public class DeadLetterBooker {

    private final DeadLetterMessageDecoder decoder;
    private final DeadLetterFailureClassifier classifier;
    private final DeadLetterLedgerService ledgerService;

    public DeadLetterBooker(DeadLetterMessageDecoder decoder,
                            DeadLetterFailureClassifier classifier,
                            DeadLetterLedgerService ledgerService) {
        this.decoder = decoder;
        this.classifier = classifier;
        this.ledgerService = ledgerService;
    }

    /**
     * Book one delivered DLQ message under the shared derivation rules.
     *
     * @param deadLetterQueueName the queue the message was read from (bound by the
     *                            caller — never guessed from headers)
     * @return the new ledger row id, or empty when the message was already booked
     */
    public Optional<Long> book(String deadLetterQueueName, Message message) {
        DeadLetterSignal signal = decoder.decode(deadLetterQueueName, message);
        DeadLetterLedgerInput input = new DeadLetterLedgerInput(
                signal.messageFingerprint(),
                deadLetterQueueName,
                signal.origExchange(),
                signal.origRoutingKey(),
                signal.payloadText(),
                // : classify from the captured listener failure text when the
                // recoverer provided one; the x-death reason stays the fallback.
                classifier.classify(signal.failureText(), signal.xDeathReason()),
                signal.failureText(),
                signal.xDeathJson());
        return ledgerService.record(input);
    }
}
