package com.sixbean.testpike.aiservice.llm.domain.component;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** Factory default exchange budget in seconds. Single home for the value
     *  (application.yml binds ${LLM_TIMEOUT_SECONDS:300} at install time);
     *  referenced by the OpenAiCompatClient null-budget fallback so no second
     *  literal exists. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 800;

    /** Factory default inter-line idle bound (seconds) for streaming reads. Single
     *  home for the value; the K8 key ai.llm.stream-idle-timeout-seconds overrides
     *  it per call through LlmTimeoutResolver (hot update, never hardcoded at use
     *  sites). 90s sits above every observed healthy provider gap yet well below
     *  the 300s exchange budget, so a heartbeat-fed drip is cut early. */
    public static final int DEFAULT_STREAM_IDLE_SECONDS = 90;

 
    private boolean mockEnabled = true;

 
    private String provider = "openai";

    
    private String baseUrl = "http://localhost:18080/v1";

    
    private String apiKey = "";

    
    private String model = "gpt-4o-mini";

    
    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    /** Streaming inter-line idle bound (seconds). K8 key ai.llm.stream-idle-timeout-seconds
     *  overrides per call through LlmTimeoutResolver (hot update); this value is the
     *  install-time fallback, never a hard literal at use sites. */
    private int streamIdleTimeoutSeconds = DEFAULT_STREAM_IDLE_SECONDS;

    public int getStreamIdleTimeoutSeconds() {
        return streamIdleTimeoutSeconds;
    }

    public void setStreamIdleTimeoutSeconds(int streamIdleTimeoutSeconds) {
        this.streamIdleTimeoutSeconds = streamIdleTimeoutSeconds;
    }

    public boolean isMockEnabled() {
        return mockEnabled;
    }

    public void setMockEnabled(boolean mockEnabled) {
        this.mockEnabled = mockEnabled;
    }

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

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
