package com.sixbean.testpike.aiservice.llm.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmProperties;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmProviderFactory;
import com.sixbean.testpike.aiservice.llm.domain.service.LlmProvider;
import com.sixbean.testpike.aiservice.llm.infrastructure.client.OpenAiCompatClient;

import java.time.Duration;


public class OpenAiCompatLlmProvider implements LlmProvider {

    private final LlmProperties properties;
    private final OpenAiCompatClient client;

    public OpenAiCompatLlmProvider(LlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("LLM_API_KEY is not configured (llm.api-key is empty): production mode must inject the provider key from .env");
        }
        // R56: mint through the floored factory seam — a raw constructor here
        // bypassed the 800s model-request floor whenever llm.timeout-seconds
        // was seeded or overridden below it (defect A assembly inventory).
        this.client = LlmProviderFactory.mintFlooredClient(
                properties.getBaseUrl(),
                properties.getApiKey(),
                Duration.ofSeconds(properties.getTimeoutSeconds()),
                objectMapper);
    }

    @Override
    public String providerName() {
        return properties.getProvider();
    }

    @Override
    public LlmChatResult chat(ChatCompletionRequest request) {
        return client.chat(request, providerName());
    }
}
