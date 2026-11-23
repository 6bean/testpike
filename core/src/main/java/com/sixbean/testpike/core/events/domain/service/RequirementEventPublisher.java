package com.sixbean.testpike.core.events.domain.service;

import com.sixbean.testpike.core.events.api.dto.RequirementEventMessage;

/**
 * Requirement event publisher (save-chain side effects on the shared event bus).
 * <p>Publish semantics mirror {@link CaseEventPublisher}: when a transaction is
 * active the send is deferred to afterCommit (never emitted on rollback); without
 * a transaction it sends immediately. Delivery failures are logged, never blocking
 * the business main flow.
 */
public interface RequirementEventPublisher {

    /**
     * Publish a requirement event (takes effect after transaction commit).
     *
     * @param message event payload (eventType must be a legal {@link
     *                com.sixbean.testpike.core.events.api.dto.RequirementEventType} name)
     */
    void publish(RequirementEventMessage message);
}
