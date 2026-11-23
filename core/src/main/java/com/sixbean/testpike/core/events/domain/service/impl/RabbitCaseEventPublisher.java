package com.sixbean.testpike.core.events.domain.service.impl;

import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.events.api.dto.CaseEventMessage;
import com.sixbean.testpike.core.events.api.dto.CaseEventType;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import com.sixbean.testpike.core.events.domain.service.CaseEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;


@Service
public class RabbitCaseEventPublisher implements CaseEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitCaseEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final AfterCommitExecutor afterCommitExecutor;

    public RabbitCaseEventPublisher(RabbitTemplate rabbitTemplate,
                                    AfterCommitExecutor afterCommitExecutor) {
        this.rabbitTemplate = rabbitTemplate;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    @Override
    public void publish(CaseEventMessage message) {
        if (message == null) {
            return;
        }
        afterCommitExecutor.execute(() -> doSend(message));
    }

    private void doSend(CaseEventMessage message) {
        try {
            CaseEventType type = CaseEventType.valueOf(message.eventType());
            rabbitTemplate.convertAndSend(RabbitEventConfig.EXCHANGE, type.routingKey(), message);
            log.info("事件已发布 eventType={} routingKey={} caseId={} projectId={} operatorId={}",
                    type.name(), type.routingKey(), message.caseId(), message.projectId(), message.operatorId());
        } catch (RuntimeException e) {
            log.error("事件发布失败 eventType={} caseId={} projectId={}（已记录，不阻断业务）",
                    message.eventType(), message.caseId(), message.projectId(), e);
        }
    }
}
