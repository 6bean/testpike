package com.sixbean.testpike.core.linkage.support;

import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;

import java.util.UUID;

/**
 * Shared test probe for the generic changed-event family: binds a temporary
 * exclusive auto-delete queue onto the exchange routing key of one object type
 * and decodes arriving messages. Real RabbitMQ round trip, no mocks — single
 * source for every suite that must observe a published object-changed event
 * (same posture as RequirementEventQueueProbe for the requirement family).
 */
public class ObjectChangedEventQueueProbe {

    private final AmqpAdmin amqpAdmin;
    private final RabbitTemplate rabbitTemplate;
    private final MessageConverter converter;

    public ObjectChangedEventQueueProbe(AmqpAdmin amqpAdmin, RabbitTemplate rabbitTemplate,
                                        MessageConverter converter) {
        this.amqpAdmin = amqpAdmin;
        this.rabbitTemplate = rabbitTemplate;
        this.converter = converter;
    }

    /** Declare a temp queue bound to "{objectType}.changed" for the given routing key. */
    public Queue bindRoutingKey(String routingKey) {
        Queue queue = new Queue("tp.test." + UUID.randomUUID(), false, true, true);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(new Binding(queue.getName(), Binding.DestinationType.QUEUE,
                RabbitEventConfig.EXCHANGE, routingKey, null));
        return queue;
    }

    /** Receive the next object-changed event from the queue, or null after the timeout. */
    public ObjectChangedEventMessage await(Queue queue, long timeoutMs) {
        Message msg = rabbitTemplate.receive(queue.getName(), timeoutMs);
        return msg == null ? null : (ObjectChangedEventMessage) converter.fromMessage(msg);
    }

    /**
     * Receive and decode any message from the queue without assuming its type
     * — used by suites that observe a different member of the bus family (e.g.
     * the linkage proposal request) through the same probe wiring.
     */
    public Object awaitRaw(Queue queue, long timeoutMs) {
        Message msg = rabbitTemplate.receive(queue.getName(), timeoutMs);
        return msg == null ? null : converter.fromMessage(msg);
    }
}
