package com.sixbean.testpike.aiservice.aichat.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.mq.AiMqDeadLetter;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dialog-run queue configuration (AO batch step C of γ; mirrors
 * MockGenQueueConfig exactly): durable queue {@code testpike.ai-chat} bound to
 * the shared {@code testpike.events} topic exchange under route key {@code
 * ai.chat.run} — the same literal core RabbitChatRunPublisher sends with,
 * single-sourced through {@link AiChatRunMessage#ROUTING_KEY}.
 * <p>The dead-letter routing key is pinned to the queue name explicitly (the
 * AiGenQueueConfig run1 lesson: inheriting the original route key mismatches
 * the DLQ binding and messages vanish silently). Exchange bean is reused from
 * the gen domain — a second declaration of the same name with diverging
 * arguments is the classic admin-status footgun.
 */
@Configuration
public class ChatRunQueueConfig {

    /** Durable queue — a consumer crash must not drop an asked question. */
    public static final String QUEUE_NAME = "testpike.ai-chat";

    @Bean
    public Queue aiChatRunQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(AiMqDeadLetter.DLX_EXCHANGE)
                .deadLetterRoutingKey(QUEUE_NAME)
                .build();
    }

    /** Dead-letter queue of the dialog family (shared DLX declaration in gen domain). */
    @Bean
    public Queue aiChatRunDeadLetterQueue() {
        return QueueBuilder.durable(AiMqDeadLetter.dlqName(QUEUE_NAME)).build();
    }

    @Bean
    public Binding aiChatRunDeadLetterBinding(Queue aiChatRunDeadLetterQueue,
                                              DirectExchange aiMqDeadLetterExchange) {
        return BindingBuilder.bind(aiChatRunDeadLetterQueue).to(aiMqDeadLetterExchange)
                .with(QUEUE_NAME);
    }

    @Bean
    public Binding aiChatRunBinding(Queue aiChatRunQueue, TopicExchange aiGenExchange) {
        return BindingBuilder.bind(aiChatRunQueue).to(aiGenExchange)
                .with(AiChatRunMessage.ROUTING_KEY);
    }
}
