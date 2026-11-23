package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage;

/**
 * Generic object-changed publisher — the bus side of the change-linkage loop.
 * Any save chain announces {@code {objectType}.changed} with its field diff so
 * edge detectors become the single consumer entry point instead of per-domain
 * hooks.
 * <p>Publish semantics mirror {@link com.sixbean.testpike.core.events.domain.service.RequirementEventPublisher}:
 * with an active transaction the send is deferred to afterCommit (a rollback
 * emits nothing); without one it sends immediately. Delivery failures are
 * logged, never blocking the business main flow.
 */
public interface ObjectChangedEventPublisher {

    /**
     * Announce that one object changed (takes effect after transaction commit).
     *
     * @param message event payload; {@code eventType} must name an
     *                {@link com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType},
     *                anything else is dropped with a log line
     */
    void publish(ObjectChangedEventMessage message);
}
