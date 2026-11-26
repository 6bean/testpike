package com.sixbean.testpike.aiservice.llm.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.EmbeddingCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmEmbeddingResult;
import com.sixbean.testpike.aiservice.llm.domain.service.LlmProvider;
import com.sixbean.testpike.aiservice.llm.infrastructure.client.OpenAiCompatClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.time.Duration;


public class LlmProviderFactory {

    private final LlmProvider provider;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;

    public LlmProviderFactory(LlmProvider provider, LlmProperties properties, ObjectMapper objectMapper) {
        this.provider = provider;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    
    public String currentProviderName() {
        return provider.providerName();
    }

    
    public boolean isMock() {
        return "mock".equals(provider.providerName());
    }

    
    public String currentModel() {
        return properties.getModel();
    }

    
    public LlmChatResult chat(ChatCompletionRequest request) {
        return provider.chat(request);
    }

    
    public LlmChatResult chatFor(ChatCompletionRequest request, LlmEndpointConfig endpoint) {
        return chatFor(request, endpoint, Duration.ofSeconds(properties.getTimeoutSeconds()));
    }

    /**
 * Streaming variant of {@link #chatFor} with the endpoint's default timeout
 * (AO batch dialog kernel, .
 */
    public LlmChatResult chatStreamFor(ChatCompletionRequest request, LlmEndpointConfig endpoint,
                                       java.util.function.Consumer<LlmStreamEvent> onEvent) {
        return chatStreamFor(request, endpoint,
                Duration.ofSeconds(properties.getTimeoutSeconds()), onEvent);
    }

    /**
     * Streaming call per routed endpoint (AO batch dialog kernel): forwards every
     * provider chunk to {@code onEvent} while returning the same aggregated
     * {@link LlmChatResult} shape as {@link #chatFor}. Mock stand-in mode passes
     * the whole answer through with <b>no</b> delta events — fabricating a fake
     * incremental stream would defeat the "never leave the user waiting" honesty
     * the frame protocol is built on (the orchestrator labels mock explicitly).
     */
    public LlmChatResult chatStreamFor(ChatCompletionRequest request, LlmEndpointConfig endpoint,
                                       Duration timeout,
                                       java.util.function.Consumer<LlmStreamEvent> onEvent) {
        return chatStreamFor(request, endpoint, timeout,
                Duration.ofSeconds(properties.getStreamIdleTimeoutSeconds()), onEvent);
    }

    /**
     * Streaming call with an explicit inter-line idle bound (K8-hot value resolved by
     * LlmTimeoutResolver per call; the client re-arms it over the SSE read loop so a
     * heartbeat-fed drip cannot outlive the exchange budget).
     */
    public LlmChatResult chatStreamFor(ChatCompletionRequest request, LlmEndpointConfig endpoint,
                                       Duration timeout, Duration streamIdleTimeout,
                                       java.util.function.Consumer<LlmStreamEvent> onEvent) {
        if (isMock()) {
            return provider.chat(request);
        }
        // R56 assembly seam: a production exchange budget is floored here,
        // at the only place real provider clients are minted. The inter-line
        // stream idle bound is a named exemption (it guards frame gaps, not
        // request duration) and passes through untouched.
        OpenAiCompatClient client = new OpenAiCompatClient(
                endpoint.baseUrl(),
                endpoint.apiKey(),
                OpenAiCompatClient.floorModelRequestBudget(timeout),
                streamIdleTimeout,
                objectMapper);
        return client.chatStream(request, endpoint.provider(), onEvent);
    }

    /**
     * R56 floored mint seam: the only sanctioned way to build a production
     * OpenAiCompatClient outside this class. Every real exchange budget is
     * raised to the model-request floor here, so no assembly site can ship a
     * sub-floor hop by forgetting the floor call (defect A inventory closed:
     * chat / chatStream / embed legs below + the default provider bean).
     */
    public static OpenAiCompatClient mintFlooredClient(String baseUrl, String apiKey,
                                                       Duration timeout,
                                                       ObjectMapper objectMapper) {
        return new OpenAiCompatClient(baseUrl, apiKey,
                OpenAiCompatClient.floorModelRequestBudget(timeout), objectMapper);
    }

    
    public LlmChatResult chatFor(ChatCompletionRequest request, LlmEndpointConfig endpoint,
                                 Duration timeout) {
        if (isMock()) {
            return provider.chat(request);
        }
        OpenAiCompatClient client = mintFlooredClient(
                endpoint.baseUrl(), endpoint.apiKey(), timeout, objectMapper);
        return client.chat(request, endpoint.provider());
    }

    
    public LlmEmbeddingResult embedFor(EmbeddingCompletionRequest request,
                                       EmbeddingEndpointConfig endpoint, Duration timeout) {
        if (isMock()) {
            throw new ServiceException(
                    "embedding 服务不可用：当前为 mock 替身模式，请配置真实 embedding 供应商"
                            + "（llm.mock-enabled=false + 大模型清单启用 embedding 模型；"
                            + "该开关在启动时装配，改后需 docker compose up -d ai-service 重建生效）",
                    BizErrorCode.AI_PROVIDER_ERROR);
        }
        OpenAiCompatClient client = mintFlooredClient(
                endpoint.baseUrl(), endpoint.apiKey(), timeout, objectMapper);
        return client.embed(request, endpoint.provider());
    }
}
