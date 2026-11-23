package com.sixbean.testpike.aiservice.gen.infrastructure.mq;

import com.sixbean.testpike.common.log.LogCollapseWindow;
import org.slf4j.Logger;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.util.ErrorHandler;

/**
 * Collapsing listener-error handler for the MQ dead-letter storm family
 * ai-service side wiring; the suppression mechanism itself is
 * the single-sourced common {@link LogCollapseWindow}, R40: this is its
 * fourth-family consumer, no per-service repeat filter).
 * <p>Behaviour: when the retry advice exhausts and the failure-capture recoverer
 * rethrows, Spring AMQP's container error handler logged the same fingerprint
 * once per delivery — under a poison-message replay storm that floods the
 * service log while the authoritative record lives on the dead-letter ledger
 * page (R53: logs are noise-reducible, ledgers are not). This handler keeps the
 * decision semantics byte-identical and collapses only the emission: the
 * window-open occurrence prints the full line with the attached stack, repeats
 * inside the window print nothing, the first hit after the window prints the
 * line plus the suppressed count and the ledger pointer — collapsing never
 * silently swallows.
 * <p>Rejection parity: an {@link AmqpRejectAndDontRequeueException} anywhere in
 * the cause chain is rethrown untouched so the container still nacks without
 * requeue even if {@code default-requeue-rejected} config drifts; an ordinary
 * failure returns normally and the container's requeue decision stays with the
 * properties, exactly as before. Composition over the default
 * ConditionalRejectingErrorHandler (red line: no subclassing) — only its
 * storm log is replaced, never its contract.
 * <p>Window hot-tunable through the system property
 * {@value #WINDOW_PROPERTY} (60s fallback; {@code <=0} disables collapsing so
 * every line prints — the documented fall-open for incident forensics).
 */
public final class MqListenerFailureCollapseHandler implements ErrorHandler {

    /** Hot-tunable window key, surfaced for tests/ops (same convention as the
 * tp.log.llm-* collapse keys). */
    public static final String WINDOW_PROPERTY = "tp.log.mq-deadletter-collapse-window-ms";

    /** Fingerprint family prefix: dead-letter storms only ever collapse
     *  against their own family. */
    private static final String FAMILY_PREFIX = "mq-deadletter|";

    /** Rollover note pointing at the authoritative record (R53). */
    private static final String LEDGER_NOTE = "完整失败链见死信台账页";

    private final LogCollapseWindow window;
    private final Logger sink;

    /** Production seam: window resolves from the hot-tunable property per check. */
    public MqListenerFailureCollapseHandler(Logger sink) {
        this(new LogCollapseWindow(WINDOW_PROPERTY), sink);
    }

    /** Test seam: fixed clock/window pinned by the caller (no public escape hatch). */
    public MqListenerFailureCollapseHandler(LogCollapseWindow window, Logger sink) {
        this.window = window;
        this.sink = sink;
    }

    @Override
    public void handleError(Throwable t) {
        String line = describe(t);
        String printed = window.admit(FAMILY_PREFIX + LogCollapseWindow.normalize(line), line, LEDGER_NOTE);
        if (printed != null) {
            sink.error(printed, t);
        }
        AmqpRejectAndDontRequeueException reject = firstRejectSignal(t);
        if (reject != null) {
            throw reject;
        }
    }

    /**
     * Single-line failure digest: exchange + routing key + consumer queue +
     * root cause family — the identity fields the fingerprint is keyed on
     * (digits masked downstream, so attempt counters and timestamps never
     * split one logical storm family).
     */
    private static String describe(Throwable t) {
        Message failed = t instanceof ListenerExecutionFailedException lefe ? lefe.getFailedMessage() : null;
        MessageProperties props = failed == null ? null : failed.getMessageProperties();
        String exchange = props == null ? "-" : props.getReceivedExchange();
        String routingKey = props == null ? "-" : props.getReceivedRoutingKey();
        String queue = props == null ? "-" : props.getConsumerQueue();
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return MessageFormatter.arrayFormat(
                "MQ 消费失败进死信 exchange={} routingKey={} queue={} 根因={}: {}",
                new Object[]{exchange, routingKey, queue, root.getClass().getSimpleName(), root.getMessage()})
                .getMessage();
    }

    /** Cause-chain walk for the reject marker (library-private logic of the
     *  default handler, re-expressed here over an interface implementation —
     *  subclassing is red-lined, six lines of traversal are not a duplicated
     *  repo component). */
    private static AmqpRejectAndDontRequeueException firstRejectSignal(Throwable t) {
        for (Throwable c = t; c != null && c.getCause() != c; c = c.getCause()) {
            if (c instanceof AmqpRejectAndDontRequeueException reject) {
                return reject;
            }
        }
        return null;
    }
}
