package com.sixbean.testpike.aiservice.spec.infrastructure.mq;

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
public class SpecExtractQueueConfig {

    
    public static final String QUEUE_NAME = "testpike.ai-spec-extract";

    
    public static final String ROUTING_SPEC_EXTRACT = "ai.spec.extract";

    @Bean
    public Queue aiSpecExtractQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(AiMqDeadLetter.DLX_EXCHANGE)
                .deadLetterRoutingKey(QUEUE_NAME)
                .build();
    }

    
    @Bean
    public Queue aiSpecExtractDeadLetterQueue() {
        return QueueBuilder.durable(AiMqDeadLetter.dlqName(QUEUE_NAME)).build();
    }

    @Bean
    public Binding aiSpecExtractDeadLetterBinding(Queue aiSpecExtractDeadLetterQueue,
                                                  DirectExchange aiMqDeadLetterExchange) {
        return BindingBuilder.bind(aiSpecExtractDeadLetterQueue).to(aiMqDeadLetterExchange)
                .with(QUEUE_NAME);
    }

    
    @Bean
    public Binding aiSpecExtractBinding(Queue aiSpecExtractQueue, TopicExchange aiGenExchange) {
        return BindingBuilder.bind(aiSpecExtractQueue).to(aiGenExchange).with(ROUTING_SPEC_EXTRACT);
    }
}
