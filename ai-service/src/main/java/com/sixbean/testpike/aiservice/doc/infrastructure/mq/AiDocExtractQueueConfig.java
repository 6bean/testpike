package com.sixbean.testpike.aiservice.doc.infrastructure.mq;

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
public class AiDocExtractQueueConfig {

    
    public static final String QUEUE_NAME = "testpike.ai-doc-extract";

    
    public static final String EXCHANGE = "testpike.events";

    
    public static final String ROUTING_DOC_EXTRACT = "ai.doc.extract";

    @Bean
    public Queue aiDocExtractQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(AiMqDeadLetter.DLX_EXCHANGE)
                .deadLetterRoutingKey(QUEUE_NAME)
                .build();
    }

 
    @Bean
    public Queue aiDocExtractDeadLetterQueue() {
        return QueueBuilder.durable(AiMqDeadLetter.dlqName(QUEUE_NAME)).build();
    }

    @Bean
    public Binding aiDocExtractDeadLetterBinding(Queue aiDocExtractDeadLetterQueue,
                                                 DirectExchange aiMqDeadLetterExchange) {
        return BindingBuilder.bind(aiDocExtractDeadLetterQueue).to(aiMqDeadLetterExchange)
                .with(QUEUE_NAME);
    }

    
    @Bean
    public Binding aiDocExtractBinding(Queue aiDocExtractQueue, TopicExchange aiGenExchange) {
        return BindingBuilder.bind(aiDocExtractQueue).to(aiGenExchange).with(ROUTING_DOC_EXTRACT);
    }
}
