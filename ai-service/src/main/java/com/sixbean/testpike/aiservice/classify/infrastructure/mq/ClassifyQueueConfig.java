package com.sixbean.testpike.aiservice.classify.infrastructure.mq;

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
public class ClassifyQueueConfig {

    
    public static final String QUEUE_NAME = "testpike.ai-failure-classify";

    
    public static final String ROUTING_EXEC_TASK_FAILED = "exec.task.failed";

    @Bean
    public Queue classifyQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(AiMqDeadLetter.DLX_EXCHANGE)
                .deadLetterRoutingKey(QUEUE_NAME)
                .build();
    }

 
    @Bean
    public Queue classifyDeadLetterQueue() {
        return QueueBuilder.durable(AiMqDeadLetter.dlqName(QUEUE_NAME)).build();
    }

    @Bean
    public Binding classifyDeadLetterBinding(Queue classifyDeadLetterQueue,
                                             DirectExchange aiMqDeadLetterExchange) {
        return BindingBuilder.bind(classifyDeadLetterQueue).to(aiMqDeadLetterExchange)
                .with(QUEUE_NAME);
    }

    
    @Bean
    public Binding classifyBinding(Queue classifyQueue, TopicExchange aiGenExchange) {
        return BindingBuilder.bind(classifyQueue).to(aiGenExchange)
                .with(ROUTING_EXEC_TASK_FAILED);
    }
}
