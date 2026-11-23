package com.sixbean.testpike.aiservice.explore.infrastructure.mq;

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
public class ExploreQueueConfig {

    
    public static final String QUEUE_NAME = "testpike.ai-explore";

    
    public static final String ROUTING_EXPLORE_RUN = "ai.explore.run";

    @Bean
    public Queue aiExploreQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(AiMqDeadLetter.DLX_EXCHANGE)
                .deadLetterRoutingKey(QUEUE_NAME)
                .build();
    }

 
    @Bean
    public Queue aiExploreDeadLetterQueue() {
        return QueueBuilder.durable(AiMqDeadLetter.dlqName(QUEUE_NAME)).build();
    }

    @Bean
    public Binding aiExploreDeadLetterBinding(Queue aiExploreDeadLetterQueue,
                                              DirectExchange aiMqDeadLetterExchange) {
        return BindingBuilder.bind(aiExploreDeadLetterQueue).to(aiMqDeadLetterExchange)
                .with(QUEUE_NAME);
    }

    
    @Bean
    public Binding aiExploreBinding(Queue aiExploreQueue, TopicExchange aiGenExchange) {
        return BindingBuilder.bind(aiExploreQueue).to(aiGenExchange).with(ROUTING_EXPLORE_RUN);
    }
}
