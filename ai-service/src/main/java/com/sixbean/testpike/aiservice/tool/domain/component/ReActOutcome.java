package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;

import java.util.List;


public record ReActOutcome(
        LlmChatResult result,
        List<ChatMessage> messages,
        int rounds,
        int dispatched,
        int rejected,
        boolean limitReached,
        com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig activeEndpoint) {
}
