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
public class KbIngestQueueConfig {

    
    public static final String QUEUE_NAME = "testpike.ai-kb-ingest";

    
    public static final String ROUTING_KB_INGEST = "ai.kb.ingest";

    @Bean
    public Queue kbIngestQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(AiMqDeadLetter.DLX_EXCHANGE)
                .deadLetterRoutingKey(QUEUE_NAME)
                .build();
    }

 
    @Bean
    public Queue kbIngestDeadLetterQueue() {
        return QueueBuilder.durable(AiMqDeadLetter.dlqName(QUEUE_NAME)).build();
    }

    @Bean
    public Binding kbIngestDeadLetterBinding(Queue kbIngestDeadLetterQueue,
                                             DirectExchange aiMqDeadLetterExchange) {
        return BindingBuilder.bind(kbIngestDeadLetterQueue).to(aiMqDeadLetterExchange)
                .with(QUEUE_NAME);
    }

    
    @Bean
    public Binding kbIngestBinding(Queue kbIngestQueue, TopicExchange aiGenExchange) {
        return BindingBuilder.bind(kbIngestQueue).to(aiGenExchange).with(ROUTING_KB_INGEST);
    }
}
