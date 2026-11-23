package com.sixbean.testpike.core.events.domain.service.impl;

import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Routing selection between the regular FIFO queue and the
 * verification-window priority queue for AI generation tasks.
 */
class RabbitAiGenPublisherWindowRoutingTest {

    private RabbitTemplate rabbitTemplate;
    private RabbitAiGenPublisher publisher;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        publisher = new RabbitAiGenPublisher(rabbitTemplate, mock(AfterCommitExecutor.class));
    }

    private AiGenMessage message(int taskType) {
        AiGenMessage message = mock(AiGenMessage.class);
        when(message.taskType()).thenReturn(taskType);
        return message;
    }

    @Test
    void regularFeedKeepsBaseRoutingKeys() {
        publisher.publish(message(1), false);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitEventConfig.EXCHANGE), eq("ai.case.generate"), any(Object.class));
        publisher.publish(message(2), false);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitEventConfig.EXCHANGE), eq("ai.script.generate"), any(Object.class));
    }

    @Test
    void windowFeedRoutesToPriorityKeys() {
        publisher.publish(message(1), true);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitEventConfig.EXCHANGE), eq("ai.case.generate.window"), any(Object.class));
        publisher.publish(message(2), true);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitEventConfig.EXCHANGE), eq("ai.script.generate.window"), any(Object.class));
    }

    @Test
    void oneArgPublishKeepsRegularRouting() {
        publisher.publish(message(1));
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitEventConfig.EXCHANGE), eq("ai.case.generate"), any(Object.class));
    }
}
