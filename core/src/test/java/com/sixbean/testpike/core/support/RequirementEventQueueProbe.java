package com.sixbean.testpike.core.support;

import com.sixbean.testpike.core.events.api.dto.RequirementEventMessage;
import com.sixbean.testpike.core.events.api.dto.RequirementEventType;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;

import java.util.UUID;

/**
 * Shared test probe for requirement-family events: binds a temporary
 * exclusive auto-delete queue onto the requirement exchange routing key and
 * decodes arriving messages. Real RabbitMQ round trip, no mocks — single
 * source for every suite that must observe a published requirement event.
 */
public class RequirementEventQueueProbe {

    private final AmqpAdmin amqpAdmin;
    private final RabbitTemplate rabbitTemplate;
    private final MessageConverter converter;

    public RequirementEventQueueProbe(AmqpAdmin amqpAdmin, RabbitTemplate rabbitTemplate,
                                      MessageConverter converter) {
        this.amqpAdmin = amqpAdmin;
        this.rabbitTemplate = rabbitTemplate;
        this.converter = converter;
    }

    /** Declare a temp queue bound to the given event type's routing key. */
    public Queue bind(RequirementEventType type) {
        Queue queue = new Queue("tp.test." + UUID.randomUUID(), false, true, true);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(new Binding(queue.getName(), Binding.DestinationType.QUEUE,
                RabbitEventConfig.EXCHANGE, type.routingKey(), null));
        return queue;
    }

    /** Receive the next requirement event from the queue, or null after the timeout. */
    public RequirementEventMessage await(Queue queue, long timeoutMs) {
        Message msg = rabbitTemplate.receive(queue.getName(), timeoutMs);
        return msg == null ? null : (RequirementEventMessage) converter.fromMessage(msg);
    }
}
