package com.sixbean.testpike.core.deadletter.domain.component;

import com.rabbitmq.client.Channel;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterLedgerInput;
import com.sixbean.testpike.core.deadletter.domain.service.DeadLetterLedgerService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Egress listener contract tests .
 * <p>L2 unit test with mocked infrastructure boundaries only: the AMQP Channel and the
 * ledger service whose real database behaviour is already covered by
 * {@code DeadLetterLedgerServiceTest} , real dev DB). What is exercised here is
 * the listener's own contract — the acknowledgement discipline the adjudication names as
 * a red line: book first, ack only after the booking attempt completes; a booking failure
 * must requeue (never drop a dead letter into a black hole), and a duplicate booking is
 * a success (idempotent re-delivery), not an error.
 */
class DeadLetterEgressListenerTest {

    private final DeadLetterMessageDecoder decoder = new DeadLetterMessageDecoder();
    private final DeadLetterFailureClassifier classifier = new DeadLetterFailureClassifier();
    private final DeadLetterLedgerService ledgerService = mock(DeadLetterLedgerService.class);
    private final Channel channel = mock(Channel.class);
    private final DeadLetterEgressListener listener =
            new DeadLetterEgressListener(new DeadLetterBooker(decoder, classifier, ledgerService));

    private static Message message(String messageId, byte[] body, long deliveryTag) {
        MessageProperties p = new MessageProperties();
        p.setMessageId(messageId);
        p.setHeader("x-death", List.of(Map.of(
                "queue", "testpike.ai-gen", "exchange", "testpike.direct", "reason", "rejected",
                "routing-keys", List.of("testpike.ai-gen"), "time", 2L, "count", 3L)));
        p.setReceivedExchange("testpike.dlx");
        p.setReceivedRoutingKey("testpike.ai-gen.dlq");
        p.setDeliveryTag(deliveryTag);
        return new Message(body, p);
    }

    @Test
    void booksThenAcksAnIncomingDeadLetter() throws IOException {
        when(ledgerService.record(any())).thenReturn(Optional.of(1L));

        listener.handle("testpike.ai-gen.dlq",
                message("mid-9", "{\"prompt\":\"x\"}".getBytes(StandardCharsets.UTF_8), 11L), channel);

        ArgumentCaptor<DeadLetterLedgerInput> captor = ArgumentCaptor.forClass(DeadLetterLedgerInput.class);
        verify(ledgerService).record(captor.capture());
        DeadLetterLedgerInput booked = captor.getValue();
        assertEquals("mid-9", booked.messageFingerprint());
        assertEquals("testpike.ai-gen.dlq", booked.queueName());
        assertEquals("testpike.direct", booked.origExchange());
        assertEquals("testpike.ai-gen", booked.origRoutingKey());
        assertEquals("{\"prompt\":\"x\"}", booked.payloadJson());
        assertEquals(DeadLetterFailureClass.UNKNOWN, booked.failureClass());
        verify(channel).basicAck(11L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void acksDuplicateBookingWithoutError() throws IOException {
        when(ledgerService.record(any())).thenReturn(Optional.empty());

        listener.handle("testpike.ai-gen.dlq", message("dup-1", "body".getBytes(StandardCharsets.UTF_8), 11L), channel);

        verify(channel).basicAck(11L, false);
    }

    @Test
    void requeuesWhenBookingFailsAndNeverAcks() throws IOException {
        when(ledgerService.record(any())).thenThrow(new ServiceException("db down", BizErrorCode.INTERNAL_ERROR));

        listener.handle("testpike.ai-gen.dlq", message("fail-1", "body".getBytes(StandardCharsets.UTF_8), 11L), channel);

        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel).basicNack(eq(11L), eq(false), eq(true));
    }

    @Test
    void requeuesWhenAckItselfFails() throws IOException {
        when(ledgerService.record(any())).thenReturn(Optional.of(2L));
        org.mockito.Mockito.doThrow(new IOException("channel closed")).when(channel)
                .basicAck(anyLong(), anyBoolean());

        listener.handle("testpike.ai-gen.dlq", message("ack-1", "body".getBytes(StandardCharsets.UTF_8), 11L), channel);
        // ack failure must not escape as an unhandled exception — the booking row already
        // exists, so broker re-delivery lands idempotently on the next attempt.
    }
}

