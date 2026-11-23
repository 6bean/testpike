package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.events.api.dto.RequirementEventType;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType;
import com.sixbean.testpike.core.linkage.support.ObjectChangedEventQueueProbe;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Object-changed event publisher test — real RabbitMQ round trip, no mocks
 * (same posture as RequirementEventPublisherTest). Verifies the generic
 * changed:{objectType} family reaches a queue bound to the family routing key
 * with a complete payload, that a rolled-back transaction emits nothing
 * (afterCommit discipline), that an unknown type is dropped without breaking
 * the caller, and that the requirement member of the family keeps the exact
 * routing key the requirement bus already uses (one protocol, no second one).
 * <p>Collaborators are pulled from the application context through one fixture
 * seam instead of repeating the per-bean field-injection block the other
 * publisher suites each carry — the probe wiring stays single-sourced in
 * ObjectChangedEventQueueProbe and this class adds no copy of it.
 */
@SpringBootTest(classes = CoreApplication.class)
class ObjectChangedEventPublisherTest {

    @Autowired
    private ApplicationContext context;

    private ObjectChangedEventPublisher publisher() {
        return context.getBean(ObjectChangedEventPublisher.class);
    }

    private ObjectChangedEventQueueProbe busProbe() {
        return new ObjectChangedEventQueueProbe(context.getBean(AmqpAdmin.class),
                context.getBean(RabbitTemplate.class),
                context.getBean("caseEventMessageConverter", MessageConverter.class));
    }

    @Test
    void publish_case_changed_reaches_bound_queue_with_full_payload() {
        Queue queue = busProbe().bindRoutingKey(ObjectChangedEventType.CASE.routingKey());
        publisher().publish(new ObjectChangedEventMessage(
                ObjectChangedEventType.CASE.name(), 42L, 7L, 9L, 3,
                List.of("title", "expectation"), System.currentTimeMillis()));

        ObjectChangedEventMessage evt = busProbe().await(queue, 5000);
        assertNotNull(evt, "case.changed must really arrive on a bound queue");
        assertEquals(ObjectChangedEventType.CASE.name(), evt.eventType());
        assertEquals(42L, evt.objectId());
        assertEquals(7L, evt.projectId(), "projectId travels with every event (H1 isolation)");
        assertEquals(9L, evt.operatorId());
        assertEquals(3, evt.objectVersion(),
                "event carries the post-write version so consumers can dedupe");
        assertEquals(List.of("title", "expectation"), evt.changedFields(),
                "the field diff rides on the announcement so the edge predicate can arm");
        assertNotNull(evt.occurredAt());
    }

    @Test
    void rolled_back_transaction_publishes_no_event() {
        Queue queue = busProbe().bindRoutingKey(ObjectChangedEventType.CASE.routingKey());
        TransactionTemplate template =
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        template.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                publisher().publish(new ObjectChangedEventMessage(
                        ObjectChangedEventType.CASE.name(), 43L, 7L, 9L, 1,
                        List.of("title"), System.currentTimeMillis()));
                status.setRollbackOnly();
            }
        });

        assertNull(busProbe().await(queue, 1500),
                "afterCommit discipline: a rolled-back save must never announce a change");
    }

    @Test
    void unknown_event_type_is_dropped_without_breaking_the_caller() {
        publisher().publish(new ObjectChangedEventMessage(
                "NO_SUCH_TYPE", 44L, 7L, 9L, 1, List.of(), System.currentTimeMillis()));
        // No bound queue can receive it; the contract is simply that the call
        // returned normally — publishing failures must never block the main flow.
    }

    @Test
    void requirement_family_member_keeps_the_existing_bus_routing_key() {
        assertEquals(RequirementEventType.CHANGED.routingKey(),
                ObjectChangedEventType.REQUIREMENT.routingKey(),
                "requirement.changed stays one protocol: the generic family must not invent a second key");
    }
}
