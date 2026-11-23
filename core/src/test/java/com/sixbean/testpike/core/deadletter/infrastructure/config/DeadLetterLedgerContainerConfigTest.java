package com.sixbean.testpike.core.deadletter.infrastructure.config;

import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterBooker;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterEgressListener;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterFailureClassifier;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterMessageDecoder;
import com.sixbean.testpike.core.deadletter.domain.service.DeadLetterLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * DLQ ledger listener container wiring tests .
 * <p>Pure JVM wiring assertions against a mocked ConnectionFactory: the two contracts
 * that matter operationally are (1) one container per dead-letter queue covering the
 * full egress surface — the live scan says 16 business queues, not the eleven from the
 * October stock count — and (2) containers never auto-start before the existing-stock
 * migration gate passes (AJ hard gate: migrate + per-queue diff=0 first, only then
 * listeners may consume/ack), plus MANUAL acknowledgement so a booking failure cannot
 * silently drop a message.
 */
class DeadLetterLedgerContainerConfigTest {

    private static DeadLetterEgressListener realListener() {
        return new DeadLetterEgressListener(new DeadLetterBooker(new DeadLetterMessageDecoder(),
                new DeadLetterFailureClassifier(), mock(DeadLetterLedgerService.class)));
    }

    @Test
    void buildsOneManualAckContainerPerDlqWithAutoStartupGatedOff() {
        DeadLetterLedgerContainerProperties props = new DeadLetterLedgerContainerProperties();
        props.setEnabled(false);
        DeadLetterLedgerContainerConfig config = new DeadLetterLedgerContainerConfig(
                mock(ConnectionFactory.class), props, realListener());

        List<SimpleMessageListenerContainer> containers = config.containers();

        assertEquals(16, containers.size(), "egress surface = 16 DLQs from the live queue scan");
        for (SimpleMessageListenerContainer container : containers) {
            String[] names = container.getQueueNames();
            assertEquals(1, names.length);
            assertTrue(names[0].endsWith(".dlq"), "container must consume the DLQ, not the business queue");
            assertFalse(container.isAutoStartup(), "hard gate: no consumption before the stock migration clears");
            assertNotNull(container.getMessageListener(), "each container needs the egress listener");
        }
        assertEquals(AcknowledgeMode.MANUAL,
                DeadLetterLedgerContainerConfig.acknowledgeMode());
    }

    @Test
    void enabledFlagFlipsAutoStartupForTheGateHandover() {
        DeadLetterLedgerContainerProperties props = new DeadLetterLedgerContainerProperties();
        props.setEnabled(true);
        DeadLetterLedgerContainerConfig config = new DeadLetterLedgerContainerConfig(
                mock(ConnectionFactory.class), props, realListener());

        assertTrue(config.containers().get(0).isAutoStartup());
    }

    @Test
    void lifecycleStartsEveryContainerWhenTheGateIsOpen() {
        // The containers arrive as a collection bean, so the context never drives
        // each element's SmartLifecycle — without this handover the gate flag
        // would flip and every DLQ would still sit at zero consumers (the exact
        // stall the dead-letter closed-loop L3 caught on 2026-11-06).
        DeadLetterLedgerContainerProperties props = new DeadLetterLedgerContainerProperties();
        props.setEnabled(true);
        List<SimpleMessageListenerContainer> containers = List.of(
                mock(SimpleMessageListenerContainer.class),
                mock(SimpleMessageListenerContainer.class),
                mock(SimpleMessageListenerContainer.class));

        DeadLetterEgressLifecycle lifecycle = new DeadLetterEgressLifecycle(containers, props);
        lifecycle.start();

        for (SimpleMessageListenerContainer container : containers) {
            org.mockito.Mockito.verify(container).start();
        }
        assertTrue(lifecycle.isRunning(), "open gate = the egress surface is live");
    }

    @Test
    void lifecycleRefusesToStartAnythingWhileTheGateIsClosed() {
        DeadLetterLedgerContainerProperties props = new DeadLetterLedgerContainerProperties();
        props.setEnabled(false);
        SimpleMessageListenerContainer container = mock(SimpleMessageListenerContainer.class);

        DeadLetterEgressLifecycle lifecycle = new DeadLetterEgressLifecycle(List.of(container), props);
        lifecycle.start();

        org.mockito.Mockito.verify(container, org.mockito.Mockito.never()).start();
        assertFalse(lifecycle.isRunning(), "closed gate must keep every DLQ unconsumed");
    }

    @Test
    void registersEveryEgressDlqAsDurableArgumentFreeQueueDeclarable() {
        // A slot vhost only carries the queues of the services that have actually run
        // there. When the producer of a given DLQ has never started on the vhost, the
        // ledger containers passively re-declare it forever — the /tp-slot4 live scan
        // caught 14 missing queues at once, a 404 declare storm in the core log and the
        // egress gate opening at only 2/16 listeners. The ledger must therefore declare
        // its own egress surface, durable and argument-free so it stays identical to the
        // producer-side QueueBuilder.durable(dlq).build() (a shared vhost must never hit
        // a PRECONDITION_FAILED property conflict between the two declaration paths).
        DeadLetterLedgerContainerProperties props = new DeadLetterLedgerContainerProperties();
        DeadLetterLedgerContainerConfig config = new DeadLetterLedgerContainerConfig(
                mock(ConnectionFactory.class), props, realListener());

        Declarables declarables = config.deadLetterEgressQueueDeclarables();
        List<Queue> queues = declarables.getDeclarablesByType(Queue.class);

        assertEquals(DeadLetterLedgerContainerConfig.egressDlqNames().stream().sorted().toList(),
                queues.stream().map(Queue::getName).sorted().toList(),
                "every egress DLQ must ship as a declarable queue, no drift from the topology list");
        for (Queue queue : queues) {
            assertTrue(queue.isDurable(), "DLQ stock survives a broker restart — durable is the ledger contract");
            org.junit.jupiter.api.Assertions.assertFalse(queue.isExclusive());
            org.junit.jupiter.api.Assertions.assertFalse(queue.isAutoDelete());
            assertEquals(java.util.Map.of(), queue.getArguments(),
                    "argument-free matches the producer declaration; differing args would deadlock the vhost on re-declare");
        }
        for (Declarable declarable : declarables.getDeclarables()) {
            assertTrue(declarable instanceof Queue, "the egress declarables carry queues only");
        }
    }

    @Test
    void dlqNamesCoverTheSixteenBusinessQueuesExactly() {
        List<String> expected = List.of(
                "testpike.mock-hit.dlq", "testpike.mock-recording-frame.dlq",
                "testpike.ai-gen.dlq", "testpike.ai-doc-extract.dlq", "testpike.ai-spec-extract.dlq",
                "testpike.ai-judge.dlq", "testpike.ai-explore.dlq", "testpike.ai-kb-ingest.dlq",
                "testpike.ai-kb-case-ingest.dlq", "testpike.ai-kb-case-cleanup.dlq",
                "testpike.ai-training-sample.dlq", "testpike.ai-failure-classify.dlq",
                "testpike.ai-script-repair.dlq", "testpike.ai-chat.dlq",
                "testpike.ai-batch-insight.dlq", "testpike.ai-mock-gen.dlq");
        assertEquals(expected.stream().sorted().toList(),
                DeadLetterLedgerContainerConfig.egressDlqNames().stream().sorted().toList(),
                "egress list must match the live-queue scan without drift");
    }
}
