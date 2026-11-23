package com.sixbean.testpike.core.llmconfig.api.dto;

import com.sixbean.testpike.core.config.domain.component.ConfigCipher;

/**
 *
 * @param cacheHint wiring — tp_llm_config.cache_hint (1 = the caller may
 * attach the provider prompt-cache key on outbound requests;
 * null = column-less legacy source = enabled by default)
 * @param structuredOutput wiring — tp_llm_config.structured_output
 * (1 = the endpoint accepts a response_format block; null =
 * column-less legacy source = capable by default, refusal is
 * degraded at the consumption point)
 */
public record LlmConfigBrief(long id, String name, String provider, String modelName,
                             String apiKey, String baseUrl, String source,
                             Integer promptLimitTokens, String tokenizerFamily,
                             Integer modelType, Integer embeddingDimension,
                             Boolean cacheHint, Boolean structuredOutput) {

    /** Compatibility constructor (pre cache-hint callers = null = enabled default). */
    public LlmConfigBrief(long id, String name, String provider, String modelName,
                          String apiKey, String baseUrl, String source,
                          Integer promptLimitTokens, String tokenizerFamily,
                          Integer modelType, Integer embeddingDimension) {
        this(id, name, provider, modelName, apiKey, baseUrl, source, promptLimitTokens,
                tokenizerFamily, modelType, embeddingDimension, null, null);
    }

    
    public LlmConfigBrief(long id, String name, String provider, String modelName,
                          String apiKey, String baseUrl, String source,
                          Integer promptLimitTokens, String tokenizerFamily) {
        this(id, name, provider, modelName, apiKey, baseUrl, source, promptLimitTokens,
                tokenizerFamily, null, null);
    }

    
    public LlmConfigBrief(long id, String name, String provider, String modelName,
                          String apiKey, String baseUrl, String source) {
        this(id, name, provider, modelName, apiKey, baseUrl, source, null, null);
    }

    
    public LlmConfigBrief(long id, String name, String provider, String modelName,
                          String apiKey, String baseUrl, String source,
                          Integer promptLimitTokens) {
        this(id, name, provider, modelName, apiKey, baseUrl, source, promptLimitTokens, null);
    }

    
    public static final String SOURCE_PROJECT = "project";

    
    public static final String SOURCE_DEFAULT = "default";

    /**
     * Rendering guard (AGENTS red line 4): the record carries the decrypted
     * provider key, and assertion/surefire/log dumps print DTOs through
     * toString — so the key component is always replaced by the shared mask
     * (null stays null = honest "no key"). Everything else renders verbatim
     * for debugging.
     */
    @Override
    public String toString() {
        return "LlmConfigBrief[id=" + id + ", name=" + name + ", provider=" + provider
                + ", modelName=" + modelName
                + ", apiKey=" + (apiKey == null ? "null" : ConfigCipher.MASK)
                + ", baseUrl=" + baseUrl + ", source=" + source
                + ", promptLimitTokens=" + promptLimitTokens
                + ", tokenizerFamily=" + tokenizerFamily + ", modelType=" + modelType
                + ", embeddingDimension=" + embeddingDimension
                + ", cacheHint=" + cacheHint + ", structuredOutput=" + structuredOutput + "]";
    }
}
