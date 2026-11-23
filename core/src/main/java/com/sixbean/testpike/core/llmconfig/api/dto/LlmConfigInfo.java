package com.sixbean.testpike.core.llmconfig.api.dto;


public record LlmConfigInfo(long id, String name, String provider, String modelName,
                            String apiKeyMasked, String baseUrl, int status, int isDefault,
                            String description, int promptLimitTokens, String tokenizerFamily,
                            int modelType, Integer embeddingDimension) {

    
    public LlmConfigInfo(long id, String name, String provider, String modelName,
                         String apiKeyMasked, String baseUrl, int status, int isDefault,
                         String description, int promptLimitTokens, String tokenizerFamily) {
        this(id, name, provider, modelName, apiKeyMasked, baseUrl, status, isDefault,
                description, promptLimitTokens, tokenizerFamily,
                LlmConfigInput.TYPE_GENERATION, null);
    }

    
    public LlmConfigInfo(long id, String name, String provider, String modelName,
                         String apiKeyMasked, String baseUrl, int status, int isDefault,
                         String description) {
        this(id, name, provider, modelName, apiKeyMasked, baseUrl, status, isDefault,
                description, LlmConfigInput.DEFAULT_PROMPT_LIMIT_TOKENS, "auto");
    }

    
    public LlmConfigInfo(long id, String name, String provider, String modelName,
                         String apiKeyMasked, String baseUrl, int status, int isDefault,
                         String description, int promptLimitTokens) {
        this(id, name, provider, modelName, apiKeyMasked, baseUrl, status, isDefault,
                description, promptLimitTokens, "auto");
    }
}
