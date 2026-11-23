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
public class CaseIngestQueueConfig {

    
    public static final String QUEUE_NAME = "testpike.ai-kb-case-ingest";

    
    public static final String ROUTING_CASE_REVIEWED = "case.reviewed";

    /** Routing key mirroring core RequirementEventType.REVIEW_PASSED (requirement
     *  approved → auto ingest, dispatched by the consumer via requirementId). */
    public static final String ROUTING_REQUIREMENT_REVIEW_PASSED = "requirement.review-passed";

    /** Routing key mirroring core RequirementEventType.CHANGED (requirement
     *  edited / withdrawn → retire so stale criteria leave the vector space). */
    public static final String ROUTING_REQUIREMENT_CHANGED = "requirement.changed";

    @Bean
    public Queue caseIngestQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(AiMqDeadLetter.DLX_EXCHANGE)
                .deadLetterRoutingKey(QUEUE_NAME)
                .build();
    }

 
    @Bean
    public Queue caseIngestDeadLetterQueue() {
        return QueueBuilder.durable(AiMqDeadLetter.dlqName(QUEUE_NAME)).build();
    }

    @Bean
    public Binding caseIngestDeadLetterBinding(Queue caseIngestDeadLetterQueue,
                                               DirectExchange aiMqDeadLetterExchange) {
        return BindingBuilder.bind(caseIngestDeadLetterQueue).to(aiMqDeadLetterExchange)
                .with(QUEUE_NAME);
    }

    
    @Bean
    public Binding caseIngestBinding(Queue caseIngestQueue, TopicExchange aiGenExchange) {
        return BindingBuilder.bind(caseIngestQueue).to(aiGenExchange).with(ROUTING_CASE_REVIEWED);
    }

    /** Requirement family rides the same queue: single FIFO preserves core
     *  publish order, so the CHANGED→REVIEW_PASSED fallback pair is consumed
     *  retire-first and ends ACTIVE (cross-queue races would break that). */
    @Bean
    public Binding requirementReviewPassedBinding(Queue caseIngestQueue, TopicExchange aiGenExchange) {
        return BindingBuilder.bind(caseIngestQueue).to(aiGenExchange)
                .with(ROUTING_REQUIREMENT_REVIEW_PASSED);
    }

    @Bean
    public Binding requirementChangedBinding(Queue caseIngestQueue, TopicExchange aiGenExchange) {
        return BindingBuilder.bind(caseIngestQueue).to(aiGenExchange)
                .with(ROUTING_REQUIREMENT_CHANGED);
    }
}
