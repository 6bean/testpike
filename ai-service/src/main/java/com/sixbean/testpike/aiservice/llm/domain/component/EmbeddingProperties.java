package com.sixbean.testpike.aiservice.llm.domain.component;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "llm.embedding")
public class EmbeddingProperties {

    
    private String provider = "openai";

    
    private String baseUrl = "";

    
    private String apiKey = "";

    
    private String model = "text-embedding-v3";

    
    private Integer dimension = 1536;

    
    private int batchSize = 128;

    /**
     * Conservative default per-request input cap an embedding provider accepts.
     * Used whenever the routed model row carries no explicit cap and the K8 key
     * ai.embedding.provider-batch-cap is absent/invalid (provider incident: the
     * dashscope-compatible text-embedding family hard-rejects batches above 10).
     */
    private int providerBatchCap = 10;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getDimension() {
        return dimension;
    }

    public void setDimension(Integer dimension) {
        this.dimension = dimension;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getProviderBatchCap() {
        return providerBatchCap;
    }

    public void setProviderBatchCap(int providerBatchCap) {
        this.providerBatchCap = providerBatchCap;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
