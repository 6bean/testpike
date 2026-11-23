package com.sixbean.testpike.core.deadletter.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.SmartLifecycle;

import java.util.List;

/**
 * Gate handover for the DLQ ledger egress listeners (AJ batch, stock-migration hard gate:
 * migrate the existing DLQ stock, prove a per-queue reconciliation diff of zero,
 * only then may listeners consume and ack).
 * <p>Why this exists: {@code DeadLetterLedgerContainerConfig} returns the containers
 * as a collection bean, so the Spring context never drives each element's own
 * lifecycle — flipping {@code tp.mq.deadletter.egress.enabled} alone would leave
 * every dead-letter queue at zero consumers (caught live by the dead-letter
 * closed-loop L3 on 2026-11-06: stock stayed on the broker, nothing was booked).
 * This bean IS a context-managed lifecycle participant: when the gate is open it
 * starts every container exactly once, when the gate is closed it refuses to
 * touch any of them, and a graceful shutdown stops them again.
 * <p>Startup failure of one container is logged and swallowed by design: the
 * remaining DLQs must still drain (partial outage beats total silence), and the
 * operator surface for the stall is the broker consumer count plus the core log.
 */
public class DeadLetterEgressLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterEgressLifecycle.class);

    private final List<SimpleMessageListenerContainer> containers;
    private final DeadLetterLedgerContainerProperties properties;

    private volatile boolean running;

    public DeadLetterEgressLifecycle(List<SimpleMessageListenerContainer> containers,
                                     DeadLetterLedgerContainerProperties properties) {
        this.containers = List.copyOf(containers);
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!properties.isEnabled()) {
            log.info("Dead-letter egress gate closed — {} DLQ containers stay unstarted "
                    + "(stock migration must clear first)", containers.size());
            return;
        }
        int started = 0;
        for (SimpleMessageListenerContainer container : containers) {
            try {
                container.start();
                started++;
            } catch (RuntimeException failure) {
                log.warn("Dead-letter egress container failed to start: {}", failure.getMessage());
            }
        }
        running = started > 0;
        log.info("Dead-letter egress gate open: {}/{} DLQ ledger listeners started",
                started, containers.size());
    }

    @Override
    public synchronized void stop() {
        for (SimpleMessageListenerContainer container : containers) {
            try {
                container.stop();
            } catch (RuntimeException failure) {
                log.warn("Dead-letter egress container failed to stop cleanly: {}", failure.getMessage());
            }
        }
        running = false;
    }

    /** Runs after the bean factory has materialised the collection bean. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1024;
    }

    @Override
    public boolean isAutoStartup() {
        // the decision itself stays inside start() so the closed-gate branch logs
        // its reason instead of silently doing nothing
        return true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
