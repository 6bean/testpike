package com.sixbean.testpike.core.deadletter.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime gate for the DLQ ledger listeners .
 * <p>{@code enabled} stays false until the AJ hard gate passes: the existing
 * dev/prod DLQ stock must first be migrated into tp_mq_dead_letter with a
 * per-queue reconciliation diff of zero, only then may listeners consume and ack
 * (ack-before-migration would drop stock into a black hole). The flag is read at
 * container wiring time; flipping it plus a rolling restart hands over.
 */
@ConfigurationProperties(prefix = "tp.mq.deadletter.egress")
public class DeadLetterLedgerContainerProperties {

    /** Whether the DLQ egress listeners may auto-start. Default false (hard gate). */
    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
