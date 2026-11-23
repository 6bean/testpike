package com.sixbean.testpike.core.llmconfig.api.dto;


public record LlmConfigInput(String name, String provider, String modelName, String apiKey,
                             String baseUrl, int status, int isDefault, String description,
                             Integer promptLimitTokens, String tokenizerFamily,
                             Integer modelType, Integer embeddingDimension) {

    
    public LlmConfigInput(String name, String provider, String modelName, String apiKey,
                          String baseUrl, int status, int isDefault, String description) {
        this(name, provider, modelName, apiKey, baseUrl, status, isDefault, description, null, null,
                null, null);
    }

    
    public LlmConfigInput(String name, String provider, String modelName, String apiKey,
                          String baseUrl, int status, int isDefault, String description,
                          Integer promptLimitTokens) {
        this(name, provider, modelName, apiKey, baseUrl, status, isDefault, description,
                promptLimitTokens, null, null, null);
    }

 
    public LlmConfigInput(String name, String provider, String modelName, String apiKey,
                          String baseUrl, int status, int isDefault, String description,
                          Integer promptLimitTokens, String tokenizerFamily) {
        this(name, provider, modelName, apiKey, baseUrl, status, isDefault, description,
                promptLimitTokens, tokenizerFamily, null, null);
    }

 
    public static final int DEFAULT_PROMPT_LIMIT_TOKENS = 1048576;
    public static final int PROMPT_LIMIT_MIN = 1024;
    public static final int PROMPT_LIMIT_MAX = 10000000;

    
    public int promptLimitOrDefault() {
        return promptLimitTokens == null || promptLimitTokens <= 0
                ? DEFAULT_PROMPT_LIMIT_TOKENS : promptLimitTokens;
    }

 
    public String tokenizerFamilyOrDefault() {
        return tokenizerFamily == null || tokenizerFamily.isBlank() ? "auto" : tokenizerFamily.trim();
    }

 
    public static final java.util.List<String> TOKENIZER_FAMILIES =
            java.util.List.of("auto", "qwen", "deepseek", "cl100k", "sp-char");

 
    public static final int TYPE_GENERATION = 1;
 
    public static final int TYPE_EMBEDDING = 2;
    
    public static final int EMBEDDING_DIM_MIN = 1;
 
    public static final int EMBEDDING_DIM_MAX = 16000;

 
    public int modelTypeOrDefault() {
        return modelType == null ? TYPE_GENERATION : modelType;
    }

    
    public boolean embeddingDomain() {
        return modelTypeOrDefault() == TYPE_EMBEDDING;
    }
}
