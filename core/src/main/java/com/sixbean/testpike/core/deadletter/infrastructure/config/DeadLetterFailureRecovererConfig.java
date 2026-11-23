package com.sixbean.testpike.core.deadletter.infrastructure.config;

import com.sixbean.testpike.common.mq.DeadLetterFailureCaptureRecoverer;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Failure-capture recoverer wiring for core's business listener containers
 * (AJ batch — the capture logic itself lives in the common component,
 * this is the one thin bean line Boot's factory configurer picks up; R40).
 * <p>The dedicated RabbitTemplate keeps the republish free of any consumer-side
 * callbacks a business template might carry.
 */
@Configuration
public class DeadLetterFailureRecovererConfig {

    @Bean
    public DeadLetterFailureCaptureRecoverer deadLetterFailureCaptureRecoverer(
            ConnectionFactory connectionFactory) {
        return new DeadLetterFailureCaptureRecoverer(new RabbitTemplate(connectionFactory));
    }
}
