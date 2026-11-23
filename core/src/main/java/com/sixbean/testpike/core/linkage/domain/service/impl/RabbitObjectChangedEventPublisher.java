package com.sixbean.testpike.core.linkage.domain.service.impl;

import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType;
import com.sixbean.testpike.core.linkage.domain.service.ObjectChangedEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * RabbitMQ-backed generic object-changed publisher — the bus half of the
 * change-linkage loop. Send is deferred through {@link AfterCommitExecutor}
 * (committed data only, a rollback emits nothing) on the shared
 * {@code testpike.events} exchange with routing key {@code {objectType}.changed}
 * (constants and converter reused from RabbitEventConfig, nothing duplicated;
 * same posture as RabbitRequirementEventPublisher). Publishing failures are
 * logged and never block the business main flow — the daily bus-vs-edge
 * reconcile job backstops a missed announcement.
 */
@Service
public class RabbitObjectChangedEventPublisher implements ObjectChangedEventPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(RabbitObjectChangedEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final AfterCommitExecutor afterCommitExecutor;

    public RabbitObjectChangedEventPublisher(RabbitTemplate rabbitTemplate,
                                             AfterCommitExecutor afterCommitExecutor) {
        this.rabbitTemplate = rabbitTemplate;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    @Override
    public void publish(ObjectChangedEventMessage message) {
        if (message == null) {
            return;
        }
        afterCommitExecutor.execute(() -> doSend(message));
    }

    private void doSend(ObjectChangedEventMessage message) {
        try {
            ObjectChangedEventType type = ObjectChangedEventType.valueOf(message.eventType());
            rabbitTemplate.convertAndSend(RabbitEventConfig.EXCHANGE, type.routingKey(), message);
            log.info("object changed event published eventType={} routingKey={} objectId={}"
                            + " projectId={} version={}",
                    type.name(), type.routingKey(), message.objectId(),
                    message.projectId(), message.objectVersion());
        } catch (RuntimeException e) {
            log.error("object changed event publish dropped eventType={} objectId={}"
                            + " projectId={} (logged, business flow unaffected)",
                    message.eventType(), message.objectId(), message.projectId(), e);
        }
    }
}
