package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.mq.AiChatRunMessage;

/**
 * Dialog run dispatch : core owns the ledger turn
 * opening, the actual reasoning runs in ai-service. Same posture as the
 * generation chains — publish over {@code testpike.events} on route
 * {@code ai.chat.run}, consumer declares its own queue binding.
 */
public interface ChatRunPublisher {

    /**
     * Publish one run request. Implementations fire only after the opening
     * transaction commits (a rolled-back turn must never reach the consumer).
     */
    void publishRun(AiChatRunMessage message);
}
