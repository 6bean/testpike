package com.sixbean.testpike.aiservice.llm.domain;

import java.util.List;


public record LlmChatResult(
        String content,
        String provider,
        String model,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        List<LlmToolCall> toolCalls,
        int cachedTokens) {

    
    public LlmChatResult(String content, String provider, String model,
                         int promptTokens, int completionTokens, long latencyMs) {
        this(content, provider, model, promptTokens, completionTokens, latencyMs, null, 0);
    }

    
    public LlmChatResult(String content, String provider, String model,
                         int promptTokens, int completionTokens, long latencyMs,
                         List<LlmToolCall> toolCalls) {
        this(content, provider, model, promptTokens, completionTokens, latencyMs, toolCalls, 0);
    }
}
