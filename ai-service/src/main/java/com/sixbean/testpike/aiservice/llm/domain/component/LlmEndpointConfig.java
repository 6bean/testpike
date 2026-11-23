package com.sixbean.testpike.aiservice.llm.domain.component;

/**
 *
 * @param cacheHint wiring — tp_llm_config.cache_hint propagated from the
 * routed brief (null = column-less fallback source = enabled)
 * @param structuredOutput wiring — tp_llm_config.structured_output
 * capability flag (null = column-less source = capable;
 * a provider that still refuses the format is degraded
 * at the consumption point with a visible log line)
 */
public record LlmEndpointConfig(String provider, String modelName, String apiKey,
                                String baseUrl, String source, Integer promptLimitTokens,
                                Long llmConfigId, String tokenizerFamily, Boolean cacheHint,
                                Boolean structuredOutput) {

    /** Factory-default-on semantics (R32): null = no flag column in this source = on. */
    public boolean cacheHintEnabled() {
        return cacheHint == null || cacheHint;
    }

    /** Same factory-on semantics: null (row flag absent) = endpoint treated as capable. */
    public boolean structuredOutputEnabled() {
        return structuredOutput == null || structuredOutput;
    }

    /** Compatibility constructor (pre structured-output callers = null = capable). */
    public LlmEndpointConfig(String provider, String modelName, String apiKey,
                             String baseUrl, String source, Integer promptLimitTokens,
                             Long llmConfigId, String tokenizerFamily, Boolean cacheHint) {
        this(provider, modelName, apiKey, baseUrl, source, promptLimitTokens, llmConfigId,
                tokenizerFamily, cacheHint, null);
    }

    /** Compatibility constructor (pre cache-hint callers = null = enabled default). */
    public LlmEndpointConfig(String provider, String modelName, String apiKey,
                             String baseUrl, String source, Integer promptLimitTokens,
                             Long llmConfigId, String tokenizerFamily) {
        this(provider, modelName, apiKey, baseUrl, source, promptLimitTokens, llmConfigId,
                tokenizerFamily, null);
    }

 
    public LlmEndpointConfig(String provider, String modelName, String apiKey,
                             String baseUrl, String source) {
        this(provider, modelName, apiKey, baseUrl, source, null, null, null);
    }

    
    public LlmEndpointConfig(String provider, String modelName, String apiKey,
                             String baseUrl, String source, Integer promptLimitTokens) {
        this(provider, modelName, apiKey, baseUrl, source, promptLimitTokens, null, null);
    }

    
    public LlmEndpointConfig(String provider, String modelName, String apiKey,
                             String baseUrl, String source, Integer promptLimitTokens,
                             Long llmConfigId) {
        this(provider, modelName, apiKey, baseUrl, source, promptLimitTokens, llmConfigId, null);
    }

    
    public static final String SOURCE_PROJECT = "project";

    
    public static final String SOURCE_DEFAULT = "default";

    
    public static final String SOURCE_CONFIG = "config";

 
    public static final String SOURCE_CONFIG_DB = "config-db";

 
    public static final String SOURCE_CHAIN = "chain";
}
