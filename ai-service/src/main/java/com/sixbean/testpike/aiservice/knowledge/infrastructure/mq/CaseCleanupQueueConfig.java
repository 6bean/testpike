package com.sixbean.testpike.aiservice.knowledge.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.mq.AiMqDeadLetter;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class CaseCleanupQueueConfig {

    
    public static final String QUEUE_NAME = "testpike.ai-kb-case-cleanup";

    
    public static final String ROUTING_CASE_DELETED = "case.deleted";

    
    public static final String ROUTING_CASE_INVALIDATED = "case.invalidated";

    
    public static final String ROUTING_PROJECT_INVALIDATED = "kb.project-invalidated";

    @Bean
    public Queue caseCleanupQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(AiMqDeadLetter.DLX_EXCHANGE)
                .deadLetterRoutingKey(QUEUE_NAME)
                .build();
    }

 
    @Bean
    public Queue caseCleanupDeadLetterQueue() {
        return QueueBuilder.durable(AiMqDeadLetter.dlqName(QUEUE_NAME)).build();
    }

    @Bean
    public Binding caseCleanupDeadLetterBinding(Queue caseCleanupDeadLetterQueue,
                                                DirectExchange aiMqDeadLetterExchange) {
        return BindingBuilder.bind(caseCleanupDeadLetterQueue).to(aiMqDeadLetterExchange)
                .with(QUEUE_NAME);
    }

    
    @Bean
    public Binding caseCleanupDeletedBinding(Queue caseCleanupQueue, TopicExchange aiGenExchange) {
        return BindingBuilder.bind(caseCleanupQueue).to(aiGenExchange).with(ROUTING_CASE_DELETED);
    }

    @Bean
    public Binding caseCleanupInvalidatedBinding(Queue caseCleanupQueue,
                                                 TopicExchange aiGenExchange) {
        return BindingBuilder.bind(caseCleanupQueue).to(aiGenExchange)
                .with(ROUTING_CASE_INVALIDATED);
    }

    @Bean
    public Binding caseCleanupProjectInvalidatedBinding(Queue caseCleanupQueue,
                                                        TopicExchange aiGenExchange) {
        return BindingBuilder.bind(caseCleanupQueue).to(aiGenExchange)
                .with(ROUTING_PROJECT_INVALIDATED);
    }
}
