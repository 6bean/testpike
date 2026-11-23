package com.sixbean.testpike.core.deadletter.domain.component;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Books every dead-lettered message into the ledger before letting it leave the DLQ
 * is the pending-row count, never the Rabbit queue depth").
 * <p>Acknowledgement discipline is the red line: the ledger write happens first, and a
 * failure to book nacks with requeue so the message stays on the DLQ instead of being
 * swallowed. A duplicate booking (empty Optional) is a normal idempotent outcome and
 * is acked — the row already exists.
 * <p>The derivation itself (decode → classify → assemble → record) lives in the
 * single-source {@link DeadLetterBooker}, shared with the stock-migration tool so a
 * pre-booked message and a listener-booked message can never disagree on fingerprint
 * , R40). The failure class comes from the classifier inside the booker; on
 * the current reject path the broker carries no exception text, so the classifier
 * decides from the x-death reason alone.
 */
@Component
public class DeadLetterEgressListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterEgressListener.class);

    private final DeadLetterBooker booker;

    public DeadLetterEgressListener(DeadLetterBooker booker) {
        this.booker = booker;
    }

    /**
     * Handle one delivered DLQ message. {@code deadLetterQueueName} is the queue the
     * container consumes (passed by the wiring, not guessed from headers).
     */
    public void handle(String deadLetterQueueName, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        boolean booked;
        try {
            booker.book(deadLetterQueueName, message);
            booked = true;
        } catch (Exception bookingFailure) {
            log.warn("dead-letter booking failed on queue {}: {} — message stays on the DLQ",
                    deadLetterQueueName, bookingFailure.getMessage());
            booked = false;
        }
        if (!booked) {
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException ackFailure) {
            // The row is already booked; the broker will re-deliver and the duplicate
            // booking acks idempotently — a nack here could touch a stale delivery tag.
            log.warn("ack failed on queue {} after successful booking: {}",
                    deadLetterQueueName, ackFailure.getMessage());
        }
    }

    /**
     * Message listener factory used by the per-DLQ containers: the queue identity is
     * bound at wiring time so the handler never relies on the (absent) consumer-queue
     * header (spring-amqp does not expose one).
     */
    public org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener forQueue(
            String deadLetterQueueName) {
        return (message, channel) -> handle(deadLetterQueueName, message, channel);
    }
}
