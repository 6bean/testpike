package com.sixbean.testpike.core.events.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.events.api.dto.RequirementEventMessage;
import com.sixbean.testpike.core.events.api.dto.RequirementEventType;
import com.sixbean.testpike.core.support.RequirementEventQueueProbe;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Requirement event publisher test — real RabbitMQ round trip, no mocks
 * (same posture as {@link CaseEventPublisherTest}). Verifies the requirement-family
 * event reaches a bound queue with a complete payload and that a rolled-back
 * transaction emits nothing (afterCommit discipline).
 */
@SpringBootTest(classes = CoreApplication.class)
class RequirementEventPublisherTest {

    @Autowired
    private RequirementEventPublisher requirementEventPublisher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private MessageConverter caseEventMessageConverter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private RequirementEventQueueProbe probe() {
        return new RequirementEventQueueProbe(amqpAdmin, rabbitTemplate, caseEventMessageConverter);
    }

    @Test
    void publish_changed_event_reaches_bound_queue_with_full_payload() {
        Queue queue = probe().bind(RequirementEventType.CHANGED);
        requirementEventPublisher.publish(new RequirementEventMessage(
                RequirementEventType.CHANGED.name(), 42L, 7L, 9L, 3, System.currentTimeMillis()));

        RequirementEventMessage evt = probe().await(queue, 5000);
        assertNotNull(evt, "requirement.changed must really arrive on a bound queue");
        assertEquals(RequirementEventType.CHANGED.name(), evt.eventType());
        assertEquals(42L, evt.requirementId());
        assertEquals(7L, evt.projectId(), "projectId travels with every event (H1)");
        assertEquals(9L, evt.operatorId());
        assertEquals(3, evt.requirementVersion(),
                "event carries the post-write version so consumers dedupe via kb-ingest:req:{id}:{version}");
        assertNotNull(evt.occurredAt());
    }

    @Test
    void rolled_back_transaction_publishes_no_event() {
        Queue queue = probe().bind(RequirementEventType.CHANGED);
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                requirementEventPublisher.publish(new RequirementEventMessage(
                        RequirementEventType.CHANGED.name(), 43L, 7L, 9L, 1,
                        System.currentTimeMillis()));
                status.setRollbackOnly();
            }
        });

        assertNull(probe().await(queue, 1500),
                "afterCommit semantics: a rolled-back transaction must not emit events");
    }
}
