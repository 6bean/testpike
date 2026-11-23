package com.sixbean.testpike.core.usage.api.dto;


public record UsageSampleInput(Long llmConfigId, String provider, String model, String chain,
                               Long projectId, int estTokens, int usagePromptTokens,
                               int completionTokens, long latencyMs, String tokenizerEngine,
                               int cachedTokens) {

    
    public UsageSampleInput(Long llmConfigId, String provider, String model, String chain,
                            Long projectId, int estTokens, int usagePromptTokens,
                            int completionTokens, long latencyMs) {
        this(llmConfigId, provider, model, chain, projectId, estTokens, usagePromptTokens,
                completionTokens, latencyMs, null, 0);
    }

    
    public UsageSampleInput(Long llmConfigId, String provider, String model, String chain,
                            Long projectId, int estTokens, int usagePromptTokens,
                            int completionTokens, long latencyMs, String tokenizerEngine) {
        this(llmConfigId, provider, model, chain, projectId, estTokens, usagePromptTokens,
                completionTokens, latencyMs, tokenizerEngine, 0);
    }
}
