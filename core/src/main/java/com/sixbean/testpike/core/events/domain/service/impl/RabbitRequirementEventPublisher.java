package com.sixbean.testpike.core.events.domain.service.impl;

import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.events.api.dto.RequirementEventMessage;
import com.sixbean.testpike.core.events.api.dto.RequirementEventType;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import com.sixbean.testpike.core.events.domain.service.RequirementEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * RabbitMQ-backed requirement event publisher.
 * <p>Send is deferred through {@link AfterCommitExecutor} (committed data only,
 * rollback emits nothing). Publishing failures never block the business main
 * flow (at-most-once skeleton; consumers dedupe idempotently — same posture as
 * the case event publisher).
 */
@Service
public class RabbitRequirementEventPublisher implements RequirementEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitRequirementEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final AfterCommitExecutor afterCommitExecutor;

    public RabbitRequirementEventPublisher(RabbitTemplate rabbitTemplate,
                                          AfterCommitExecutor afterCommitExecutor) {
        this.rabbitTemplate = rabbitTemplate;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    @Override
    public void publish(RequirementEventMessage message) {
        if (message == null) {
            return;
        }
        afterCommitExecutor.execute(() -> doSend(message));
    }

    private void doSend(RequirementEventMessage message) {
        try {
            RequirementEventType type = RequirementEventType.valueOf(message.eventType());
            rabbitTemplate.convertAndSend(RabbitEventConfig.EXCHANGE, type.routingKey(), message);
            log.info("requirement event published eventType={} routingKey={} requirementId={}"
                            + " projectId={} version={}",
                    type.name(), type.routingKey(), message.requirementId(),
                    message.projectId(), message.requirementVersion());
        } catch (RuntimeException e) {
            log.error("requirement event publish failed eventType={} requirementId={}"
                            + " projectId={} (logged, business flow unaffected)",
                    message.eventType(), message.requirementId(), message.projectId(), e);
        }
    }
}
