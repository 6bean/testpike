package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;

import java.util.List;


public record CalibrationSample(
        Long llmConfigId,
        Long projectId,
        String provider,
        String model,
        String chain,
        int estTokens,
        int usagePromptTokens,
        int completionTokens,
        long latencyMs,
        String tokenizerEngine,
        int cachedTokens) {

    
    public CalibrationSample(Long llmConfigId, Long projectId, String provider, String model,
                             String chain, int estTokens, int usagePromptTokens,
                             int completionTokens, long latencyMs) {
        this(llmConfigId, projectId, provider, model, chain, estTokens, usagePromptTokens,
                completionTokens, latencyMs, null, 0);
    }

    
    public CalibrationSample(Long llmConfigId, Long projectId, String provider, String model,
                             String chain, int estTokens, int usagePromptTokens,
                             int completionTokens, long latencyMs, String tokenizerEngine) {
        this(llmConfigId, projectId, provider, model, chain, estTokens, usagePromptTokens,
                completionTokens, latencyMs, tokenizerEngine, 0);
    }

    
    public static final String CHAIN_EXTRACT = "extract";
    public static final String CHAIN_SUMMARY = "summary";

    
    public Double deviationRate() {
        if (usagePromptTokens <= 0) {
            return null;
        }
        return (usagePromptTokens - estTokens) / (double) usagePromptTokens;
    }

    
    public static int estimateOf(TokenCounter counter, List<ChatMessage> messages) {
        return counter.countTexts(messages.stream().map(ChatMessage::textContent).toList());
    }

 
    public static int estimateOf(TokenEstimator estimator, List<ChatMessage> messages) {
        return estimator.countTexts(messages.stream().map(ChatMessage::textContent).toList());
    }
}
