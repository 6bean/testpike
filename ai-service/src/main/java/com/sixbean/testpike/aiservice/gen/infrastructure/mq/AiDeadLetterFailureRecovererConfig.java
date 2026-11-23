package com.sixbean.testpike.aiservice.gen.infrastructure.mq;

import com.sixbean.testpike.common.mq.DeadLetterFailureCaptureRecoverer;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Failure-capture recoverer wiring for ai-service's MQ consumer containers
 * (AJ batch — single-sourced in the common component, this is the one
 * thin bean line Boot's listener container factory configurer adopts; R40).
 * <p>Applies to every default-container @RabbitListener in the context, so all
 * sixteen business queues share one capture path.
 */
@Configuration
public class AiDeadLetterFailureRecovererConfig {

    @Bean
    public DeadLetterFailureCaptureRecoverer deadLetterFailureCaptureRecoverer(
            ConnectionFactory connectionFactory) {
        return new DeadLetterFailureCaptureRecoverer(new RabbitTemplate(connectionFactory));
    }
}
