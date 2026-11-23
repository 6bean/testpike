package com.sixbean.testpike.aiservice.mock.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.mq.AiMqDeadLetter;
import com.sixbean.testpike.common.mq.MockGenMessage;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI mock-drafting queue configuration ; mirrors
 * SpecExtractQueueConfig exactly): durable queue
 * {@code testpike.ai-mock-gen} bound to the shared {@code testpike.events}
 * topic exchange under route key {@code ai.mock.gen} — the same literal core
 * RabbitAiGenPublisher.publishMockGen sends with, single-sourced through
 * {@link MockGenMessage#ROUTING_KEY}.
 * <p>The dead-letter routing key is pinned to the queue name explicitly
 * (AiGenQueueConfig run1 lesson: inheriting the original route key mismatches
 * the DLQ binding and messages vanish silently). The exchange bean is reused
 * from the gen domain — a second declaration of the same name with diverging
 * arguments is the classic admin-status footgun.
 */
@Configuration
public class MockGenQueueConfig {

    /** Durable queue — a consumer crash must not drop drafted jobs. */
    public static final String QUEUE_NAME = "testpike.ai-mock-gen";

    @Bean
    public Queue aiMockGenQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(AiMqDeadLetter.DLX_EXCHANGE)
                .deadLetterRoutingKey(QUEUE_NAME)
                .build();
    }

    /** Dead-letter queue of the drafting family (shared DLX declaration in gen domain). */
    @Bean
    public Queue aiMockGenDeadLetterQueue() {
        return QueueBuilder.durable(AiMqDeadLetter.dlqName(QUEUE_NAME)).build();
    }

    @Bean
    public Binding aiMockGenDeadLetterBinding(Queue aiMockGenDeadLetterQueue,
                                              DirectExchange aiMqDeadLetterExchange) {
        return BindingBuilder.bind(aiMockGenDeadLetterQueue).to(aiMqDeadLetterExchange)
                .with(QUEUE_NAME);
    }

    @Bean
    public Binding aiMockGenBinding(Queue aiMockGenQueue, TopicExchange aiGenExchange) {
        return BindingBuilder.bind(aiMockGenQueue).to(aiGenExchange)
                .with(MockGenMessage.ROUTING_KEY);
    }
}
