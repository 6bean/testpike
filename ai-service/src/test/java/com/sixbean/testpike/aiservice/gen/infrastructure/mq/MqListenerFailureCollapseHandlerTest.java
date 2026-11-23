package com.sixbean.testpike.aiservice.gen.infrastructure.mq;

import com.sixbean.testpike.aiservice.testsupport.CapturingLogger;
import com.sixbean.testpike.common.log.LogCollapseWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the MQ dead-letter storm family must run through the
 * single-sourced {@link LogCollapseWindow} (R40: the container error handler is
 * the fourth-family consumer of the component). The failure line of a
 * rejected message is emitted by Spring AMQP's internal logger once per failed
 * delivery; under a poison-message replay storm that floods the service log
 * with the same fingerprint (R53: the ledger, not the log, is the authoritative
 * record). Contract proved here: same fingerprint inside the window emits
 * exactly one line carrying the full stack; the rollover line carries the
 * suppressed count plus the dead-letter ledger pointer (collapsing never
 * silently swallows); digits (attempt counters, timestamps, ids) must not
 * split one logical family; a non-positive resolved window (hot-tunable
 * property) prints every line; distinct exchange/routing-key families never
 * suppress each other; the reject semantics of the wrapped
 * {@link AmqpRejectAndDontRequeueException} are rethrown untouched (the
 * container must still nack without requeue — only the log noise collapses).
 */
class MqListenerFailureCollapseHandlerTest {

    private static final String PROP = "tp.log.mq-deadletter-collapse-window-ms";

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROP);
    }

    @Test
    void deadLetterSameFingerprintCollapsesThenRolloverCarriesCountAndLedgerPointer() {
        AtomicLong clock = new AtomicLong(0L);
        LogCollapseWindow window = new LogCollapseWindow(PROP, clock::get, 60_000L);
        CapturingLogger capture = new CapturingLogger();
        MqListenerFailureCollapseHandler handler =
                new MqListenerFailureCollapseHandler(window, capture.logger());

        // Window open: full line with the attached stack prints, reject is rethrown.
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> handler.handleError(rejection("ai.case.generate", "LLM timeout after attempt 1")));
        assertEquals(1, capture.errors().size(), "window-open line must print");
        assertInstanceOf(ListenerExecutionFailedException.class, capture.errorStacks().get(0),
                "the open-window line carries the full throwable (zero information loss at first sight)");
        assertTrue(capture.errors().get(0).contains("ai.case.generate"),
                "line names the routing key so operators can locate the family: " + capture.errors().get(0));

        // Second occurrence inside the window: collapsed, still one line — but the
        // reject decision repeats (semantics untouched by collapsing).
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> handler.handleError(rejection("ai.case.generate", "LLM timeout after attempt 2")));
        assertEquals(1, capture.errors().size(), "same fingerprint inside the window collapses to one line");

        // First hit after the window: rollover line with count + ledger pointer (R53).
        clock.set(61_000L);
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> handler.handleError(rejection("ai.case.generate", "LLM timeout after attempt 3")));
        assertEquals(2, capture.errors().size(), "first hit after the window rolls over");
        String rollover = capture.errors().get(1);
        assertTrue(rollover.contains("1 条同指纹重复"), "rollover carries the suppressed count: " + rollover);
        assertTrue(rollover.contains("死信台账"),
                "rollover points at the authoritative dead-letter ledger (never silent): " + rollover);
    }

    @Test
    void attemptCountersAndTimestampsMustNotSplitOneFamily() {
        LogCollapseWindow window = new LogCollapseWindow(PROP, () -> 0L, 60_000L);
        CapturingLogger capture = new CapturingLogger();
        MqListenerFailureCollapseHandler handler =
                new MqListenerFailureCollapseHandler(window, capture.logger());

        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> handler.handleError(rejection("ai.script.generate", "budget exhausted at 1794009028")));
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> handler.handleError(rejection("ai.script.generate", "budget exhausted at 1794009818")));
        assertEquals(1, capture.errors().size(),
                "digit-only variations (timestamps/counters) are masked into one fingerprint");
    }

    @Test
    void distinctRoutingKeyFamiliesNeverSuppressEachOther() {
        LogCollapseWindow window = new LogCollapseWindow(PROP, () -> 0L, 60_000L);
        CapturingLogger capture = new CapturingLogger();
        MqListenerFailureCollapseHandler handler =
                new MqListenerFailureCollapseHandler(window, capture.logger());

        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> handler.handleError(rejection("ai.case.generate", "provider refused")));
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> handler.handleError(rejection("ai.doc.extract", "provider refused")));
        assertEquals(2, capture.errors().size(),
                "different routing keys are different storm families (exchange+routing-key fingerprint)");
    }

    @Test
    void propertyNonPositiveDisablesCollapseEveryOccurrencePrints() {
        System.setProperty(PROP, "-1");
        CapturingLogger capture = new CapturingLogger();
        // Production constructor: window resolves from the hot-tunable property.
        MqListenerFailureCollapseHandler handler = new MqListenerFailureCollapseHandler(capture.logger());
        for (int i = 1; i <= 3; i++) {
            assertThrows(AmqpRejectAndDontRequeueException.class,
                    () -> handler.handleError(rejection("ai.case.generate", "flaky attempt")));
        }
        assertEquals(3, capture.errors().size(),
                "window<=0 must fall open to the legacy every-line behaviour");
    }

    @Test
    void nonRejectionFailureIsNotRethrownSoContainerNackSemanticsStayWithProperties() {
        LogCollapseWindow window = new LogCollapseWindow(PROP, () -> 0L, 60_000L);
        CapturingLogger capture = new CapturingLogger();
        MqListenerFailureCollapseHandler handler =
                new MqListenerFailureCollapseHandler(window, capture.logger());

        // An ordinary listener failure (no reject marker in the cause chain) must
        // return normally: the container decides requeue from default-requeue-rejected.
        handler.handleError(new ListenerExecutionFailedException("Failed to invoke @RabbitListener",
                new IllegalStateException("transient DB hiccup"),
                message("testpike.events", "ai.case.generate")));
        assertEquals(1, capture.errors().size(), "the failure line still prints");
    }

    private static ListenerExecutionFailedException rejection(String routingKey, String reason) {
        return new ListenerExecutionFailedException("Failed to invoke @RabbitListener",
                new AmqpRejectAndDontRequeueException(reason),
                message("testpike.events", routingKey));
    }

    private static Message message(String exchange, String routingKey) {
        MessageProperties properties = new MessageProperties();
        properties.setReceivedExchange(exchange);
        properties.setReceivedRoutingKey(routingKey);
        properties.setConsumerQueue(routingKey + ".queue");
        return new Message("payload".getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);
    }
}
