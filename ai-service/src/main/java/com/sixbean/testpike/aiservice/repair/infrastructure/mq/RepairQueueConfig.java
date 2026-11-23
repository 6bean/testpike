package com.sixbean.testpike.aiservice.repair.infrastructure.mq;

import com.sixbean.testpike.aiservice.classify.infrastructure.mq.ClassifyQueueConfig;
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
public class RepairQueueConfig {

    
    public static final String QUEUE_NAME = "testpike.ai-script-repair";

    @Bean
    public Queue repairQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(AiMqDeadLetter.DLX_EXCHANGE)
                .deadLetterRoutingKey(QUEUE_NAME)
                .build();
    }

    
    @Bean
    public Queue repairDeadLetterQueue() {
        return QueueBuilder.durable(AiMqDeadLetter.dlqName(QUEUE_NAME)).build();
    }

    @Bean
    public Binding repairDeadLetterBinding(Queue repairDeadLetterQueue,
                                           DirectExchange aiMqDeadLetterExchange) {
        return BindingBuilder.bind(repairDeadLetterQueue).to(aiMqDeadLetterExchange)
                .with(QUEUE_NAME);
    }

    
    @Bean
    public Binding repairBinding(Queue repairQueue, TopicExchange aiGenExchange) {
        return BindingBuilder.bind(repairQueue).to(aiGenExchange)
                .with(ClassifyQueueConfig.ROUTING_EXEC_TASK_FAILED);
    }
}
