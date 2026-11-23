package com.sixbean.testpike.core.linkage.infrastructure.mq;

import com.sixbean.testpike.common.mq.MqDeadLetterTopology;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Change-linkage dispatch queue : durable queue
 * {@code testpike.linkage-impact} bound to the shared event exchange
 * {@code testpike.events} with the wildcard routing key {@code *.changed}, so
 * every member of the generic object-changed family flows into the single
 * edge-detection entry — adding a new bus member must never need a new hook
 * (bus-not-scattered-hooks, design adjudication section 3.4). The exchange
 * itself stays declared by RabbitEventConfig (single source, no duplicate
 * TopicExchange bean), same posture as MockHitQueueConfig.
 * <p>Failure topology mirrors the other consumers: retry exhausted routes to
 * the dead-letter queue keyed by the queue name, so a poison announcement can
 * never be mis-routed into silence.
 */
@Configuration
public class ChangeLinkageQueueConfig {

    /** Queue name (durable — a core crash must not drop pending detection work). */
    public static final String QUEUE_NAME = "testpike.linkage-impact";

    /** Binding covering every {@code {objectType}.changed} routing key. */
    public static final String ROUTING_CHANGED_ALL = "*.changed";

    @Bean
    public Queue changeLinkageDispatchQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(MqDeadLetterTopology.DLX_EXCHANGE)
                .deadLetterRoutingKey(QUEUE_NAME)
                .build();
    }

    /** Dead-letter exchange (direct; the DLX every consumer queue converges on). */
    @Bean
    public DirectExchange linkageDeadLetterExchange() {
        return new DirectExchange(MqDeadLetterTopology.DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue changeLinkageDispatchDeadLetterQueue() {
        return QueueBuilder.durable(MqDeadLetterTopology.dlqName(QUEUE_NAME)).build();
    }

    @Bean
    public Binding changeLinkageDeadLetterBinding(Queue changeLinkageDispatchDeadLetterQueue,
                                                  DirectExchange linkageDeadLetterExchange) {
        return BindingBuilder.bind(changeLinkageDispatchDeadLetterQueue)
                .to(linkageDeadLetterExchange).with(QUEUE_NAME);
    }

    /** Business binding (exchange bean from RabbitEventConfig.caseEventExchange). */
    @Bean
    public Binding changeLinkageDispatchBinding(Queue changeLinkageDispatchQueue,
                                                TopicExchange caseEventExchange) {
        return BindingBuilder.bind(changeLinkageDispatchQueue)
                .to(caseEventExchange).with(ROUTING_CHANGED_ALL);
    }
}
