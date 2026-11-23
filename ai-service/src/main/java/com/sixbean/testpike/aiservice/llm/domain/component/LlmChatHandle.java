package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;


public record LlmChatHandle(LlmChatResult result, LlmEndpointConfig activeEndpoint) {
}
