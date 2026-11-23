package com.sixbean.testpike.aiservice.llm.domain.service;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;


public interface LlmProvider {

    
    String providerName();

    
    LlmChatResult chat(ChatCompletionRequest request);
}
