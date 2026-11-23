package com.sixbean.testpike.aiservice.sample.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.mq.AiMqDeadLetter;
import com.sixbean.testpike.aiservice.knowledge.infrastructure.mq.CaseIngestQueueConfig;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class CaseSampleQueueConfig {

    
    public static final String QUEUE_NAME = "testpike.ai-training-sample";

    @Bean
    public Queue caseSampleQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(AiMqDeadLetter.DLX_EXCHANGE)
                .deadLetterRoutingKey(QUEUE_NAME)
                .build();
    }

    
    @Bean
    public Queue caseSampleDeadLetterQueue() {
        return QueueBuilder.durable(AiMqDeadLetter.dlqName(QUEUE_NAME)).build();
    }

    @Bean
    public Binding caseSampleDeadLetterBinding(Queue caseSampleDeadLetterQueue,
                                               DirectExchange aiMqDeadLetterExchange) {
        return BindingBuilder.bind(caseSampleDeadLetterQueue).to(aiMqDeadLetterExchange)
                .with(QUEUE_NAME);
    }

    
    @Bean
    public Binding caseSampleBinding(Queue caseSampleQueue, TopicExchange aiGenExchange) {
        return BindingBuilder.bind(caseSampleQueue).to(aiGenExchange)
                .with(CaseIngestQueueConfig.ROUTING_CASE_REVIEWED);
    }
}
