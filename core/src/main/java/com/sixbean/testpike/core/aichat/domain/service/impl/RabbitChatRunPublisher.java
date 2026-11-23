package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.sixbean.testpike.common.mq.AiChatRunMessage;
import com.sixbean.testpike.core.aichat.domain.service.ChatRunPublisher;
import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ dialog-run publisher , the same shape as
 * RabbitAiGenPublisher: topic exchange {@code testpike.events}, JSON payload,
 * consumer-side queue. The send is registered on {@link AfterCommitExecutor}
 * — the two ledger rows (user echo + assistant placeholder) must be durably
 * committed before the consumer is told to stream into the placeholder; a
 * rollback publishes nothing.
 */
@Component
public class RabbitChatRunPublisher implements ChatRunPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitChatRunPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final AfterCommitExecutor afterCommitExecutor;

    public RabbitChatRunPublisher(RabbitTemplate rabbitTemplate,
                                  AfterCommitExecutor afterCommitExecutor) {
        this.rabbitTemplate = rabbitTemplate;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    @Override
    public void publishRun(AiChatRunMessage message) {
        afterCommitExecutor.execute(() -> {
            rabbitTemplate.convertAndSend(RabbitEventConfig.EXCHANGE,
                    AiChatRunMessage.ROUTING_KEY, message);
            log.info("dialog run enqueued sessionId={} assistantMessageId={} projectId={} routingKey={}",
                    message.sessionId(), message.assistantMessageId(), message.projectId(),
                    AiChatRunMessage.ROUTING_KEY);
        });
    }
}
