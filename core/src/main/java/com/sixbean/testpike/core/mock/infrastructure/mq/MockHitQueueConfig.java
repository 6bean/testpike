package com.sixbean.testpike.core.mock.infrastructure.mq;

import com.sixbean.testpike.common.mq.MqDeadLetterTopology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hit ledger batch queue : queue {@code testpike.mock-hit}
 * bound to the shared event exchange {@code testpike.events} with routing key
 * {@code mock.hit.batch} — the same exchange/constants the mock-server sink
 * publishes to. The exchange itself is declared by core RabbitEventConfig
 * (single source, no duplicate TopicExchange bean).
 * <p>Failure topology mirrors the ai-service consumer queues (CaseIngestQueueConfig
 * precedent, x-dead-letter-routing-key pinned to the queue name so a dead letter
 * can never be mis-routed into silence): retry exhausted → testpike.mock-hit.dlq,
 * where the AJ batch replay surface will pick it up.
 */
@Configuration
public class MockHitQueueConfig {

    /** Queue name (durable — a core crash must not drop buffered batches). */
    public static final String QUEUE_NAME = "testpike.mock-hit";

    /** Routing key of the hit ledger batch stream (mock-server MockHitAmqpSink twin). */
    public static final String ROUTING_HIT_BATCH = "mock.hit.batch";

    @Bean
    public Queue mockHitQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(MqDeadLetterTopology.DLX_EXCHANGE)
                .deadLetterRoutingKey(QUEUE_NAME)
                .build();
    }

    /** Dead-letter exchange (direct; same DLX the AI consumer queues converge on). */
    @Bean
    public DirectExchange mqDeadLetterExchange() {
        return new DirectExchange(MqDeadLetterTopology.DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue mockHitDeadLetterQueue() {
        return QueueBuilder.durable(MqDeadLetterTopology.dlqName(QUEUE_NAME)).build();
    }

    @Bean
    public Binding mockHitDeadLetterBinding(Queue mockHitDeadLetterQueue,
                                            DirectExchange mqDeadLetterExchange) {
        return BindingBuilder.bind(mockHitDeadLetterQueue).to(mqDeadLetterExchange)
                .with(QUEUE_NAME);
    }

    /** Business binding (exchange bean from RabbitEventConfig.caseEventExchange). */
    @Bean
    public Binding mockHitBinding(Queue mockHitQueue, TopicExchange caseEventExchange) {
        return BindingBuilder.bind(mockHitQueue).to(caseEventExchange).with(ROUTING_HIT_BATCH);
    }
}
