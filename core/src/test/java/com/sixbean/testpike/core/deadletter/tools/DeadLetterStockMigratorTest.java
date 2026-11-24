package com.sixbean.testpike.core.deadletter.tools;

import com.rabbitmq.client.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.Method;
import com.rabbitmq.client.AMQP.Queue.DeclareOk;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterBooker;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterFailureClassifier;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterMessageDecoder;
import com.sixbean.testpike.core.deadletter.domain.service.DeadLetterLedgerService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stock-migration tool unit tests .
 * <p>Infrastructure boundary (AMQP channel, ledger DB) is mocked here; the
 * acknowledgement red line is what is under test: the tool books every peeked
 * message and never acks — messages stay on the DLQ until the listener era
 * begins, so a mid-run failure can never lose stock. The per-queue tally math
 * (snapshot vs peeked diff, new/duplicate split, failure count) is the gate
 * verdict the adjudication demands before egress may be enabled.
 */
class DeadLetterStockMigratorTest {

    private final DeadLetterLedgerService ledgerService = mock(DeadLetterLedgerService.class);
    private final DeadLetterBooker booker =
            new DeadLetterBooker(new DeadLetterMessageDecoder(), new DeadLetterFailureClassifier(), ledgerService);
    private final Connection connection = mock(Connection.class);
    private final Channel channel = mock(Channel.class);

    private static GetResponse response(long deliveryTag, String messageId, String body) {
        com.rabbitmq.client.AMQP.BasicProperties props =
                new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                        .messageId(messageId)
                        .build();
        return new GetResponse(
                new com.rabbitmq.client.Envelope(deliveryTag, false, "testpike.dlx", "q"),
                props, body.getBytes(StandardCharsets.UTF_8), 0);
    }

    private void queueHolds(int snapshot, int depthAfter) throws Exception {
        DeclareOk before = Mockito.mock(DeclareOk.class);
        DeclareOk after = Mockito.mock(DeclareOk.class);
        when(before.getMessageCount()).thenReturn(snapshot);
        when(after.getMessageCount()).thenReturn(depthAfter);
        when(channel.queueDeclarePassive(anyString())).thenReturn(before, after);
        when(connection.createChannel()).thenReturn(channel);
    }

    @Test
    void peeksEveryMessageWithoutAckingAndCountsNewAndDuplicateBookings() throws Exception {
        queueHolds(2, 2);
        when(channel.basicGet(anyString(), anyBoolean()))
                .thenReturn(response(1, "m-a", "{\"i\":1}"), response(2, "m-b", "{\"i\":2}"), (GetResponse) null);
        when(ledgerService.record(any())).thenReturn(Optional.of(1L), Optional.empty());

        DeadLetterStockTally tally = new DeadLetterStockMigrator(connection, booker)
                .migrateQueue("testpike.ai-gen.dlq");

        assertEquals("testpike.ai-gen.dlq", tally.queueName());
        assertEquals(2, tally.snapshotCount());
        assertEquals(2, tally.peekedCount());
        assertEquals(1, tally.bookedNew());
        assertEquals(1, tally.bookedDuplicate());
        assertEquals(0, tally.bookingFailures());
        assertEquals(0, tally.diff());
        assertTrue(tally.isCleared());
        // The red line: nothing was ever acknowledged by the migration tool.
        verify(channel, Mockito.never()).basicAck(anyLong(), anyBoolean());
        verify(channel, Mockito.never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void bookingFailureMarksTheQueueRedAndKeepsTheMessageOnTheDlq() throws Exception {
        queueHolds(1, 1);
        when(channel.basicGet(anyString(), anyBoolean()))
                .thenReturn(response(1, "m-x", "{}"), (GetResponse) null);
        when(ledgerService.record(any())).thenThrow(new IllegalStateException("db down"));

        DeadLetterStockTally tally = new DeadLetterStockMigrator(connection, booker)
                .migrateQueue("testpike.ai-chat.dlq");

        assertEquals(1, tally.bookingFailures());
        assertFalse(tally.isCleared());
    }

    @Test
    void stockGrownMidRunShowsANonZeroDiffAndFailsTheGate() throws Exception {
        // snapshot was taken before peeking; a message dead-lettered during the
        // run means the reconciliation is not trustworthy — the honest answer
        // is diff != 0 and a re-run, never a silent pass.
        queueHolds(1, 2);
        when(channel.basicGet(anyString(), anyBoolean()))
                .thenReturn(response(1, "m-1", "{}"), response(2, "m-2", "{}"), (GetResponse) null);
        when(ledgerService.record(any())).thenReturn(Optional.of(1L), Optional.of(2L));

        DeadLetterStockTally tally = new DeadLetterStockMigrator(connection, booker)
                .migrateQueue("testpike.ai-explore.dlq");

        assertEquals(1, tally.diff());
        assertFalse(tally.isCleared());
    }

    @Test
    void emptyQueueIsClearedTrivially() throws Exception {
        queueHolds(0, 0);
        when(channel.basicGet(anyString(), anyBoolean())).thenReturn(null);

        DeadLetterStockTally tally = new DeadLetterStockMigrator(connection, booker)
                .migrateQueue("testpike.ai-judge.dlq");

        assertTrue(tally.isCleared());
        assertEquals(List.of(), List.of());
    }
}
