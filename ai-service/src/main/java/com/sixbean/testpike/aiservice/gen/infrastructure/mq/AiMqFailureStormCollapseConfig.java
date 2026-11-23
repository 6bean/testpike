package com.sixbean.testpike.aiservice.gen.infrastructure.mq;

import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the dead-letter storm collapse handler onto every simple-listener
 * container in ai-service .
 * <p>Hook choice: Spring AMQP's {@link ContainerCustomizer} is picked up by
 * Boot's own {@code rabbitListenerContainerFactory} through an ObjectProvider,
 * so the retry advice, the failure-capture recoverer bean and all
 * {@code spring.rabbitmq.listener.simple.*} properties keep Boot's wiring
 * untouched — only the container error handler is replaced. Redeclaring the
 * factory bean here would fork that wiring (R40 / behavioural drift).
 * <p>Scope: applies to every default-container {@code @RabbitListener} in the
 * context, so all business queues share one collapse window instance
 * (distinct fingerprints never suppress each other; the exchange+routing-key
 * digest keeps storm families independent).
 */
@Configuration
public class AiMqFailureStormCollapseConfig {

    @Bean
    public MqListenerFailureCollapseHandler mqListenerFailureCollapseHandler() {
        return new MqListenerFailureCollapseHandler(
                LoggerFactory.getLogger(MqListenerFailureCollapseHandler.class));
    }

    @Bean
    public ContainerCustomizer<SimpleMessageListenerContainer> collapsingListenerErrorHandler(
            MqListenerFailureCollapseHandler handler) {
        return container -> container.setErrorHandler(handler);
    }
}
