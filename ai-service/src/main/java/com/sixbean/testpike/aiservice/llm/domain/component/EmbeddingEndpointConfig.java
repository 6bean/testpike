package com.sixbean.testpike.aiservice.llm.domain.component;

/**
 *
 * @param batchCap per-request input cap enforced by this provider (model-row carried;
 * null = fall back to the K8/config conservative default)
 */
public record EmbeddingEndpointConfig(String provider, String modelName, String apiKey,
                                      String baseUrl, String source, Integer dimension,
                                      Long llmConfigId, Integer batchCap) {

    /** Compatibility constructor: no per-row provider cap (null = resolver default). */
    public EmbeddingEndpointConfig(String provider, String modelName, String apiKey,
                                   String baseUrl, String source, Integer dimension,
                                   Long llmConfigId) {
        this(provider, modelName, apiKey, baseUrl, source, dimension, llmConfigId, null);
    }

    
    public static final String SOURCE_PROJECT = "project";

    
    public static final String SOURCE_DEFAULT_EMB = "default-emb";

    
    public static final String SOURCE_CONFIG = "config";

 
    public static final String SOURCE_CHAIN = "chain";
}
