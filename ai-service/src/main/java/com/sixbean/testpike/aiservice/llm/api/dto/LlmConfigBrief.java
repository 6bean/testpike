package com.sixbean.testpike.aiservice.llm.api.dto;

/**
 *
 * @param cacheHint wiring — tp_llm_config.cache_hint mirror of the core
 * response (null = absent from an older core = enabled default)
 * @param structuredOutput wiring — tp_llm_config.structured_output mirror
 * of the core response (null = absent from an older core = capable)
 */
public record LlmConfigBrief(long id, String name, String provider, String modelName,
                             String apiKey, String baseUrl, String source,
                             Integer promptLimitTokens, String tokenizerFamily,
                             Integer modelType, Integer embeddingDimension,
                             Boolean cacheHint, Boolean structuredOutput) {

    /** Compatibility constructor (pre structured-output core payloads = null = capable). */
    public LlmConfigBrief(long id, String name, String provider, String modelName,
                          String apiKey, String baseUrl, String source,
                          Integer promptLimitTokens, String tokenizerFamily,
                          Integer modelType, Integer embeddingDimension, Boolean cacheHint) {
        this(id, name, provider, modelName, apiKey, baseUrl, source, promptLimitTokens,
                tokenizerFamily, modelType, embeddingDimension, cacheHint, null);
    }

    /** Compatibility constructor (pre cache-hint core payloads = null = enabled default). */
    public LlmConfigBrief(long id, String name, String provider, String modelName,
                          String apiKey, String baseUrl, String source,
                          Integer promptLimitTokens, String tokenizerFamily,
                          Integer modelType, Integer embeddingDimension) {
        this(id, name, provider, modelName, apiKey, baseUrl, source, promptLimitTokens,
                tokenizerFamily, modelType, embeddingDimension, null);
    }

    
    public LlmConfigBrief(long id, String name, String provider, String modelName,
                          String apiKey, String baseUrl, String source) {
        this(id, name, provider, modelName, apiKey, baseUrl, source, null, null, null, null,
                null, null);
    }

    
    public LlmConfigBrief(long id, String name, String provider, String modelName,
                          String apiKey, String baseUrl, String source, Integer promptLimitTokens) {
        this(id, name, provider, modelName, apiKey, baseUrl, source, promptLimitTokens, null, null, null);
    }

 
    public LlmConfigBrief(long id, String name, String provider, String modelName,
                          String apiKey, String baseUrl, String source,
                          Integer promptLimitTokens, String tokenizerFamily) {
        this(id, name, provider, modelName, apiKey, baseUrl, source, promptLimitTokens,
                tokenizerFamily, null, null);
    }
}
