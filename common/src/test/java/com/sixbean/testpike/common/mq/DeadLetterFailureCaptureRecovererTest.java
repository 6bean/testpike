package com.sixbean.testpike.common.mq;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Capture-recoverer contract tests .
 * <p>L2 with the template mocked (unit isolation of the broker boundary — the
 * live chain is covered by the L4 window, never by this class). Pins the four
 * fates: republish-with-text on a known queue, broker fallback (rethrow) when
 * the routing key is not ours, rethrow when the publish itself fails, and the
 * cause-chain text shape with its length cap.
 */
class DeadLetterFailureCaptureRecovererTest {

    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final DeadLetterFailureCaptureRecoverer recoverer =
            new DeadLetterFailureCaptureRecoverer(template);

    private static Message consumedMessage(String consumerQueue, String receivedRoutingKey) {
        MessageProperties p = new MessageProperties();
        p.setMessageId("m-1");
        p.setConsumerTag("ctag-1");
        p.setConsumerQueue(consumerQueue);
        p.setReceivedExchange("testpike.direct");
        p.setReceivedRoutingKey(receivedRoutingKey);
        return new Message("{\"taskId\":5}".getBytes(StandardCharsets.UTF_8), p);
    }

    @Test
    void republishesCloneWithFailureTextAndOriginHeadersForKnownQueue() {
        Message message = consumedMessage("testpike.ai-gen", "testpike.ai-gen");
        Throwable cause = new IllegalStateException("gen failed",
                new ConnectException("Connection refused"));

        recoverer.recover(message, cause);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq(MqDeadLetterTopology.DLX_EXCHANGE),
                eq("testpike.ai-gen"), captor.capture());
        Message republished = captor.getValue();
        assertEquals("{\"taskId\":5}", new String(republished.getBody(), StandardCharsets.UTF_8));
        String text = (String) republished.getMessageProperties()
                .getHeaders().get(MqDeadLetterTopology.FAILURE_TEXT_HEADER);
        assertTrue(text.contains("ConnectException: Connection refused"), text);
        assertTrue(text.contains("IllegalStateException: gen failed"), text);
        assertEquals("testpike.direct", republished.getMessageProperties()
                .getHeaders().get(MqDeadLetterTopology.DEAD_ORIGIN_EXCHANGE_HEADER));
        assertEquals("testpike.ai-gen", republished.getMessageProperties()
                .getHeaders().get(MqDeadLetterTopology.DEAD_ORIGIN_ROUTING_KEY_HEADER));
        // Delivery-scoped headers must not ride into the broker-routed clone.
        assertFalse(republished.getMessageProperties().getHeaders().containsKey(AmqpHeaders.CONSUMER_TAG));
        assertFalse(republished.getMessageProperties().getHeaders().containsKey(AmqpHeaders.CONSUMER_QUEUE));
    }

    @Test
    void republishesByQueueNameWhenLiveTrafficCarriesANonQueueRoutingKey() {
        // Live document-extract traffic publishes with routing key 'ai.doc.extract'
        // while every DLQ binding uses the queue name: capture must route the clone
        // by the consumer queue, otherwise the message falls back to the broker
        // x-death path without capture headers and the ledger stays UNKNOWN
        // (root cause proven by the L4 window run of 2026-11-06).
        Message message = consumedMessage("testpike.ai-doc-extract", "ai.doc.extract");
        Throwable cause = new IllegalStateException("extract failed",
                new ConnectException("Connection refused"));

        recoverer.recover(message, cause);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq(MqDeadLetterTopology.DLX_EXCHANGE),
                eq("testpike.ai-doc-extract"), captor.capture());
        Message republished = captor.getValue();
        assertNotNull(republished.getMessageProperties()
                .getHeaders().get(MqDeadLetterTopology.FAILURE_TEXT_HEADER));
        assertEquals("testpike.direct", republished.getMessageProperties()
                .getHeaders().get(MqDeadLetterTopology.DEAD_ORIGIN_EXCHANGE_HEADER));
        assertEquals("ai.doc.extract", republished.getMessageProperties()
                .getHeaders().get(MqDeadLetterTopology.DEAD_ORIGIN_ROUTING_KEY_HEADER));
    }

    @Test
    void rerejectsWhenConsumerQueueIsNotOneOfOurBusinessQueues() {
        // A direct DLX silently drops unroutable publishes — membership is decided
        // by the queue we consume from (that is what the DLQ bindings carry), and
        // an unknown queue hands the decision back to the broker (x-death path).
        Message message = consumedMessage("some.other.queue", "some.other.routing");
        Throwable cause = new IllegalStateException("boom");

        assertThrows(IllegalStateException.class, () -> recoverer.recover(message, cause));
        verifyNoInteractions(template);
    }

    @Test
    void rerejectsWhenTheRepublishItselfFails() {
        doThrow(new AmqpException("broker gone"))
                .when(template).send(any(String.class), any(String.class), any(Message.class));
        Message message = consumedMessage("testpike.ai-gen", "testpike.ai-gen");
        Throwable cause = new IllegalStateException("boom");

        assertThrows(IllegalStateException.class, () -> recoverer.recover(message, cause));
    }

    @Test
    void capsChainTextAtTwoThousandCharacters() {
        StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longMessage.append("fragment-").append(i).append(' ');
        }
        Message message = consumedMessage("testpike.ai-gen", "testpike.ai-gen");

        recoverer.recover(message, new RuntimeException(longMessage.toString()));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq(MqDeadLetterTopology.DLX_EXCHANGE), eq("testpike.ai-gen"), captor.capture());
        String text = (String) captor.getValue().getMessageProperties()
                .getHeaders().get(MqDeadLetterTopology.FAILURE_TEXT_HEADER);
        assertTrue(text.length() <= 2000, "chain text must respect the cap, got " + text.length());
    }

    @Test
    void requeuesImmediateRequeueCausesInsteadOfDeadLetteringThem() {
        // DD batch a listener asking for an immediate requeue
        // (crashed lock holder detected via the dead consume heartbeat) must
        // reach the broker as a requeue. Boot's exhausted-retry path hands the
        // recoverer the wrapper with the request somewhere in the cause chain;
        // republishing it to the DLX would ack the original and dead-letter a
        // task that is merely waiting for the queue.
        Message message = consumedMessage("testpike.ai-doc-extract", "ai.doc.extract");
        ImmediateRequeueAmqpException request = new ImmediateRequeueAmqpException(
                "consume lock holder crashed");
        Throwable wrapper = new RuntimeException("listener failed", request);

        assertThrows(ImmediateRequeueAmqpException.class, () -> recoverer.recover(message, wrapper));
        verifyNoInteractions(template);
    }

    @Test
    void plainFailuresWithoutARequeueRequestStillRepublishToTheDeadLetterExchange() {
        // Bidirectional guard (R43: an exclusion branch needs a hit case too):
        // the requeue shortcut must not bend any other failure — the capture
        // republish path stays byte-identical for ordinary errors.
        Message message = consumedMessage("testpike.ai-doc-extract", "ai.doc.extract");
        Throwable cause = new IllegalStateException("extract failed",
                new ConnectException("Connection refused"));

        recoverer.recover(message, cause);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq(MqDeadLetterTopology.DLX_EXCHANGE),
                eq("testpike.ai-doc-extract"), captor.capture());
        String text = (String) captor.getValue().getMessageProperties()
                .getHeaders().get(MqDeadLetterTopology.FAILURE_TEXT_HEADER);
        assertTrue(text.contains("IllegalStateException: extract failed"), text);
    }
}
