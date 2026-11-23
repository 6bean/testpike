package com.sixbean.testpike.core.deadletter.domain.component;

import com.sixbean.testpike.common.mq.MqDeadLetterTopology;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterLedgerInput;
import com.sixbean.testpike.core.deadletter.domain.service.DeadLetterLedgerService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Single-source booking contract .
 * <p>The live listener and the stock-migration tool now share one
 * decode → classify → assemble → record path. These tests pin the assembled
 * input so the two callers can never drift: the same delivered message booked
 * through the listener and through the migration tool must produce the same
 * fingerprint and the same ledger input, which is what makes the pre-booked
 * stock rows idempotency-visible to the later listener run.
 */
class DeadLetterBookerTest {

    private final DeadLetterMessageDecoder decoder = new DeadLetterMessageDecoder();
    private final DeadLetterFailureClassifier classifier = new DeadLetterFailureClassifier();
    private final DeadLetterLedgerService ledgerService = mock(DeadLetterLedgerService.class);
    private final DeadLetterBooker booker = new DeadLetterBooker(decoder, classifier, ledgerService);

    private static Message deliveredMessage(String messageId, byte[] body, Map<String, Object> headers) {
        MessageProperties p = new MessageProperties();
        p.setMessageId(messageId);
        p.setHeaders(headers);
        return new Message(body, p);
    }

    private static Map<String, Object> xDeathHeaders() {
        return Map.of("x-death", List.of(Map.of(
                "exchange", "testpike.dlx",
                "routing-keys", List.of("testpike.ai-gen"),
                "reason", "rejected",
                "queue", "testpike.ai-gen.dlq")));
    }

    @Test
    void booksDeliveredMessageWithTheSharedDerivationRules() {
        byte[] body = "{\"taskId\":7}".getBytes(StandardCharsets.UTF_8);
        Message message = deliveredMessage("m-42", body, xDeathHeaders());
        when(ledgerService.record(any())).thenReturn(Optional.of(11L));

        Optional<Long> booked = booker.book("testpike.ai-gen.dlq", message);

        assertEquals(11L, booked.orElseThrow());
        ArgumentCaptor<DeadLetterLedgerInput> captor = ArgumentCaptor.forClass(DeadLetterLedgerInput.class);
        verify(ledgerService).record(captor.capture());
        DeadLetterLedgerInput input = captor.getValue();
        assertEquals("m-42", input.messageFingerprint());
        assertEquals("testpike.ai-gen.dlq", input.queueName());
        assertEquals("testpike.dlx", input.origExchange());
        assertEquals("testpike.ai-gen", input.origRoutingKey());
        assertEquals("{\"taskId\":7}", input.payloadJson());
        assertEquals(classifier.classify(null, "rejected"), input.failureClass());
        assertTrue(input.xDeathJson().contains("rejected"));
    }

    @Test
    void duplicateDeliveryReturnsEmptyWithoutASecondRow() {
        Message message = deliveredMessage("m-dup", "{}".getBytes(StandardCharsets.UTF_8), Map.of());
        when(ledgerService.record(any())).thenReturn(Optional.empty());

        assertTrue(booker.book("testpike.ai-chat.dlq", message).isEmpty());
    }

    @Test
    void bookingFailurePropagatesSoTheCallerKeepsAckDiscipline() {
        // The booker itself never decides what happens to the message: a ledger
        // failure must surface so the listener can requeue and the migration
        // tool can count the queue red instead of swallowing the gap.
        Message message = deliveredMessage("m-boom", "x".getBytes(StandardCharsets.UTF_8), Map.of());
        when(ledgerService.record(any())).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> booker.book("testpike.ai-gen.dlq", message));
    }

    @Test
    void classifiesFromCapturedFailureTextAndCarriesItAsFailureReason() {
        // : a message republished by the capture recoverer carries the
        // listener failure text on the header — the booker must feed it to the
        // classifier (type the failure, no more UNKNOWN-by-omission) and store
        // the same text in failure_reason for operators.
        String captured = "AmqpIOException: java.net.ConnectException: Connection refused";
        Message message = deliveredMessage("m-text", "{\"taskId\":9}".getBytes(StandardCharsets.UTF_8),
                Map.of(MqDeadLetterTopology.FAILURE_TEXT_HEADER, captured));
        when(ledgerService.record(any())).thenReturn(Optional.of(21L));

        booker.book("testpike.ai-gen.dlq", message);

        ArgumentCaptor<DeadLetterLedgerInput> captor = ArgumentCaptor.forClass(DeadLetterLedgerInput.class);
        verify(ledgerService).record(captor.capture());
        assertEquals(DeadLetterFailureClass.PROVIDER_TRANSIENT, captor.getValue().failureClass());
        assertEquals(captured, captor.getValue().failureReason());
    }

    @Test
    void fallsBackToXDeathReasonOnlyWhenNoFailureTextWasCaptured() {
        // Regression guard for the pre- broker path: without the capture
        // header the classifier still decides from x-death (and stays UNKNOWN
        // when even that is missing) with a null failure reason.
        Message message = deliveredMessage("m-nodeadtext", "{}".getBytes(StandardCharsets.UTF_8),
                xDeathHeaders());
        when(ledgerService.record(any())).thenReturn(Optional.of(22L));

        booker.book("testpike.ai-gen.dlq", message);

        ArgumentCaptor<DeadLetterLedgerInput> captor = ArgumentCaptor.forClass(DeadLetterLedgerInput.class);
        verify(ledgerService).record(captor.capture());
        assertEquals(DeadLetterFailureClass.UNKNOWN, captor.getValue().failureClass());
        assertNull(captor.getValue().failureReason());
    }
}
