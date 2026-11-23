package com.sixbean.testpike.admin.llmconfig.api.dto;


public record LlmConfigSaveRequest(String name, String provider, String modelName, String apiKey,
                                   String baseUrl, int status, int isDefault, String description,
                                   Integer promptLimitTokens, String tokenizerFamily,
                                   Integer modelType, Integer embeddingDimension) {

 
    public LlmConfigSaveRequest(String name, String provider, String modelName, String apiKey,
                                String baseUrl, int status, int isDefault, String description,
                                Integer promptLimitTokens, String tokenizerFamily) {
        this(name, provider, modelName, apiKey, baseUrl, status, isDefault, description,
                promptLimitTokens, tokenizerFamily, null, null);
    }

    
    public LlmConfigSaveRequest(String name, String provider, String modelName, String apiKey,
                                String baseUrl, int status, int isDefault, String description,
                                Integer promptLimitTokens) {
        this(name, provider, modelName, apiKey, baseUrl, status, isDefault, description,
                promptLimitTokens, null);
    }
}
