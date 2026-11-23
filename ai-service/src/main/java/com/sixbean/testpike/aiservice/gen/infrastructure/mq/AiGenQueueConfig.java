package com.sixbean.testpike.aiservice.gen.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class AiGenQueueConfig {

    
    public static final String QUEUE_NAME = "testpike.ai-gen";

    /**
     * Priority channel for verification-window feeds: same payload and
     * consumer, separate queue, so window tasks only wait for a consumer
     * slot instead of the whole regular FIFO backlog.
     */
    public static final String WINDOW_QUEUE_NAME = "testpike.ai-gen-window";

    
    public static final String EXCHANGE = "testpike.events";

    
    public static final String ROUTING_CASE_GENERATE = "ai.case.generate";

    
    public static final String ROUTING_SCRIPT_GENERATE = "ai.script.generate";

    public static final String ROUTING_CASE_GENERATE_WINDOW = "ai.case.generate.window";

    public static final String ROUTING_SCRIPT_GENERATE_WINDOW = "ai.script.generate.window";

    @Bean
    public Queue aiGenQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(AiMqDeadLetter.DLX_EXCHANGE)
                .deadLetterRoutingKey(QUEUE_NAME)
                .build();
    }

 
    @Bean
    public DirectExchange aiMqDeadLetterExchange() {
        return new DirectExchange(AiMqDeadLetter.DLX_EXCHANGE, true, false);
    }

    @Bean
    public Binding aiGenDeadLetterBinding(Queue aiGenDeadLetterQueue,
                                          DirectExchange aiMqDeadLetterExchange) {
        return BindingBuilder.bind(aiGenDeadLetterQueue).to(aiMqDeadLetterExchange)
                .with(QUEUE_NAME);
    }

    @Bean
    public TopicExchange aiGenExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Binding aiGenCaseBinding(Queue aiGenQueue, TopicExchange aiGenExchange) {
        return BindingBuilder.bind(aiGenQueue).to(aiGenExchange).with(ROUTING_CASE_GENERATE);
    }

    @Bean
    public Binding aiGenScriptBinding(Queue aiGenQueue, TopicExchange aiGenExchange) {
        return BindingBuilder.bind(aiGenQueue).to(aiGenExchange).with(ROUTING_SCRIPT_GENERATE);
    }

    @Bean
    public Queue aiGenWindowQueue() {
        return QueueBuilder.durable(WINDOW_QUEUE_NAME)
                .deadLetterExchange(AiMqDeadLetter.DLX_EXCHANGE)
                // Same pitfall as aiGenQueue: DLX routing must be pinned to
                // the queue name, otherwise a poisoned window message is
                // silently dropped by the direct DLX.
                .deadLetterRoutingKey(WINDOW_QUEUE_NAME)
                .build();
    }

    @Bean
    public Binding aiGenWindowDeadLetterBinding(Queue aiGenDeadLetterQueue,
                                                DirectExchange aiMqDeadLetterExchange) {
        return BindingBuilder.bind(aiGenDeadLetterQueue).to(aiMqDeadLetterExchange)
                .with(WINDOW_QUEUE_NAME);
    }

    @Bean
    public Binding aiGenWindowCaseBinding(Queue aiGenWindowQueue, TopicExchange aiGenExchange) {
        return BindingBuilder.bind(aiGenWindowQueue).to(aiGenExchange).with(ROUTING_CASE_GENERATE_WINDOW);
    }

    @Bean
    public Binding aiGenWindowScriptBinding(Queue aiGenWindowQueue, TopicExchange aiGenExchange) {
        return BindingBuilder.bind(aiGenWindowQueue).to(aiGenExchange).with(ROUTING_SCRIPT_GENERATE_WINDOW);
    }

 
    @Bean
    public Queue aiGenDeadLetterQueue() {
        return QueueBuilder.durable(AiMqDeadLetter.dlqName(QUEUE_NAME)).build();
    }

    @Bean
    public MessageConverter aiGenMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate aiGenRabbitTemplate(ConnectionFactory connectionFactory,
                                              MessageConverter aiGenMessageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(aiGenMessageConverter);
        return rabbitTemplate;
    }
}
