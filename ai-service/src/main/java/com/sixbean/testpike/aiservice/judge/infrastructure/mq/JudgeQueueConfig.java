package com.sixbean.testpike.aiservice.judge.infrastructure.mq;

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
public class JudgeQueueConfig {

    
    public static final String QUEUE_NAME = "testpike.ai-judge";

    
    public static final String ROUTING_JUDGE_SAMPLE = "ai.judge.sample";

    @Bean
    public Queue aiJudgeQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(AiMqDeadLetter.DLX_EXCHANGE)
                .deadLetterRoutingKey(QUEUE_NAME)
                .build();
    }

 
    @Bean
    public Queue aiJudgeDeadLetterQueue() {
        return QueueBuilder.durable(AiMqDeadLetter.dlqName(QUEUE_NAME)).build();
    }

    @Bean
    public Binding aiJudgeDeadLetterBinding(Queue aiJudgeDeadLetterQueue,
                                            DirectExchange aiMqDeadLetterExchange) {
        return BindingBuilder.bind(aiJudgeDeadLetterQueue).to(aiMqDeadLetterExchange)
                .with(QUEUE_NAME);
    }

    
    @Bean
    public Binding aiJudgeBinding(Queue aiJudgeQueue, TopicExchange aiGenExchange) {
        return BindingBuilder.bind(aiJudgeQueue).to(aiGenExchange).with(ROUTING_JUDGE_SAMPLE);
    }
}
