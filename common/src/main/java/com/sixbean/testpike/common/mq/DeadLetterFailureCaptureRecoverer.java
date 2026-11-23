package com.sixbean.testpike.common.mq;

import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageBuilderSupport;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.AmqpHeaders;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Captures the listener failure text onto the rejected message and republishes
 * rows must carry a human-readable failure reason instead of UNKNOWN).
 * <p>Boot wires any {@link MessageRecoverer} bean into the listener retry advice,
 * replacing the default reject-and-don't-requeue recoverer: when this recoverer
 * returns normally the container acks the original message, so the republished
 * clone (headers annotated, body and message id untouched — the fingerprint
 * stays the same) is the single copy on the DLQ; when it rethrows the container
 * nacks with requeue=false and the broker's own x-death path produces the copy —
 * the message is never lost either way (P18: a failed capture never swallows it).
 * <p>Logic is single-sourced here (R40); core and ai-service each add one thin
 * {@code @Bean} line, no per-service copies.
 */
public class DeadLetterFailureCaptureRecoverer implements MessageRecoverer {

    /** Chain text cap: generous for operators, bounded so a pathological cause
     *  loop cannot bloat the DLQ frame. failure_reason is a TEXT column. */
    static final int FAILURE_TEXT_MAX_LEN = 2000;

    private final RabbitTemplate rabbitTemplate;

    public DeadLetterFailureCaptureRecoverer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        // DD batch a listener may ask for an immediate requeue
        // (crashed lock holder proven by the dead consume heartbeat). By the
        // time retries are exhausted the request sits in the cause chain;
        // rethrowing it here lets the container nack with requeue=true, while
        // any other failure keeps the capture path byte-identical.
        rethrowImmediateRequeueIfRequested(cause);
        // Live business traffic publishes with short routing keys ('ai.doc.extract'),
        // never the queue name, while every DLQ binding routes by queue name —
        // membership and the DLX publish must therefore key off the consumer queue
        // the container reports (L4 evidence 2026-11-06: keying off the received
        // routing key sent every extract failure down the broker fallback path with
        // no capture headers, leaving the ledger classified UNKNOWN forever).
        String queueName = message.getMessageProperties().getConsumerQueue();
        if (queueName == null || !MqDeadLetterTopology.BUSINESS_QUEUES.contains(queueName)) {
            // Not a queue we own (or unknown queue): the direct DLX would drop an
            // unroutable publish silently, so hand the fate back to the broker.
            throwAsUnchecked(cause);
        }
        try {
            rabbitTemplate.send(MqDeadLetterTopology.DLX_EXCHANGE, queueName, withCaptureHeaders(message, cause));
        } catch (RuntimeException publishFailure) {
            // Rethrow the listener failure (suppressed capture failure) → nack
            // requeue=false → broker x-death copy booked as UNKNOWN-with-reason.
            cause.addSuppressed(publishFailure);
            throwAsUnchecked(cause);
        }
    }

    /** Walk the cause chain (cycle-safe, same shape as {@link #chainText}); if any
     *  level is Spring AMQP's immediate-requeue request, hand it back to the
     *  container — ContainerUtils.isImmediateRequeueAmqpException on the escaping
     *  exception is what turns the delivery into a nack with requeue=true. */
    private static void rethrowImmediateRequeueIfRequested(Throwable cause) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable current = cause; current != null && seen.put(current, true) == null;
                current = current.getCause()) {
            if (current instanceof ImmediateRequeueAmqpException requeueRequest) {
                throw requeueRequest;
            }
        }
    }

    /** Clone the delivered message with the capture headers and the delivery-scoped
     *  headers stripped (a republished copy must not carry channel/consumer state). */
    private static Message withCaptureHeaders(Message message, Throwable cause) {
        MessageProperties properties = message.getMessageProperties();
        MessageBuilderSupport<Message> builder = MessageBuilder.fromMessage(message)
                .setHeader(MqDeadLetterTopology.FAILURE_TEXT_HEADER, chainText(cause))
                .setHeader(MqDeadLetterTopology.DEAD_ORIGIN_EXCHANGE_HEADER, properties.getReceivedExchange())
                .setHeader(MqDeadLetterTopology.DEAD_ORIGIN_ROUTING_KEY_HEADER, properties.getReceivedRoutingKey());
        for (String deliveryScoped : new String[] {
                AmqpHeaders.CHANNEL, AmqpHeaders.CONSUMER_TAG, AmqpHeaders.CONSUMER_QUEUE,
                AmqpHeaders.RECEIVED_EXCHANGE, AmqpHeaders.RECEIVED_ROUTING_KEY,
                AmqpHeaders.RECEIVED_DELAY, AmqpHeaders.RECEIVED_DELIVERY_MODE, AmqpHeaders.RECEIVED_USER_ID }) {
            builder.removeHeader(deliveryScoped);
        }
        return builder.build();
    }

    /** Flatten the cause chain into one line per level, {@code SimpleName: message}
     *  (classifier vocabulary matches over this text case-insensitively). */
    static String chainText(Throwable cause) {
        StringBuilder text = new StringBuilder();
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable current = cause; current != null && seen.put(current, true) == null;
                current = current.getCause()) {
            if (text.length() > 0) {
                text.append(" <- ");
            }
            text.append(current.getClass().getSimpleName())
                    .append(": ")
                    .append(String.valueOf(current.getMessage()));
            if (text.length() >= FAILURE_TEXT_MAX_LEN) {
                break;
            }
        }
        return text.length() <= FAILURE_TEXT_MAX_LEN
                ? text.toString()
                : text.substring(0, FAILURE_TEXT_MAX_LEN);
    }

    /** Rethrow the original throwable preserving its type; checked throwables from
     *  listener code reach us wrapped, so the unchecked branches cover practice. */
    private static void throwAsUnchecked(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("dead-letter failure capture rejected the message", cause);
    }
}
