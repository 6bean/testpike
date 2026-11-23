package com.sixbean.testpike.admin.llmconfig.api.dto;


public record LlmConfigView(long id, String name, String provider, String modelName,
                            String apiKeyMasked, String baseUrl, int status, int isDefault,
                            String description, int promptLimitTokens, String tokenizerFamily,
                            int modelType, Integer embeddingDimension) {

 
    public LlmConfigView(long id, String name, String provider, String modelName,
                         String apiKeyMasked, String baseUrl, int status, int isDefault,
                         String description, int promptLimitTokens, String tokenizerFamily) {
        this(id, name, provider, modelName, apiKeyMasked, baseUrl, status, isDefault,
                description, promptLimitTokens, tokenizerFamily, 1, null);
    }

    
    public LlmConfigView(long id, String name, String provider, String modelName,
                         String apiKeyMasked, String baseUrl, int status, int isDefault,
                         String description, int promptLimitTokens) {
        this(id, name, provider, modelName, apiKeyMasked, baseUrl, status, isDefault,
                description, promptLimitTokens, "auto");
    }
}
