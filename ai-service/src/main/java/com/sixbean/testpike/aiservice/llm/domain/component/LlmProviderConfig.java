package com.sixbean.testpike.aiservice.llm.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.service.LlmProvider;
import com.sixbean.testpike.aiservice.llm.domain.service.impl.MockLlmProvider;
import com.sixbean.testpike.aiservice.llm.domain.service.impl.OpenAiCompatLlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
@EnableConfigurationProperties({LlmProperties.class, EmbeddingProperties.class})
public class LlmProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderConfig.class);

    @Bean
    @ConditionalOnProperty(name = "llm.mock-enabled", havingValue = "false", matchIfMissing = true)
    public LlmProvider openAiCompatLlmProvider(LlmProperties properties, ObjectMapper objectMapper) {
        log.info("LLM provider assembled on real endpoint provider={} baseUrl={} model={} (secrets never logged)",
                properties.getProvider(), properties.getBaseUrl(), properties.getModel());
        return new OpenAiCompatLlmProvider(properties, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "llm.mock-enabled", havingValue = "true")
    public LlmProvider mockLlmProvider(LlmProperties properties) {
        log.info("LLM provider assembled as local mock stand-in (provider unreachable locally; production must set llm.mock-enabled=false)");
        return new MockLlmProvider(properties.getModel());
    }

    @Bean
    public LlmProviderFactory llmProviderFactory(LlmProvider llmProvider, LlmProperties properties,
                                                 ObjectMapper objectMapper) {
        return new LlmProviderFactory(llmProvider, properties, objectMapper);
    }

    
    @Bean
    public TokenCounter tokenCounter() {
        return new TokenCounter();
    }

    
    @Bean
    public LlmTimeoutResolver llmTimeoutResolver(CoreGateway coreGateway, LlmProperties properties) {
        return new LlmTimeoutResolver(coreGateway, properties);
    }

    
    @Bean
    public LlmRetryPolicy llmRetryPolicy() {
        return LlmRetryPolicy.defaults();
    }

    
    @Bean
    public EmbeddingBatchSizeResolver embeddingBatchSizeResolver(CoreGateway coreGateway,
                                                                 EmbeddingProperties embeddingProperties) {
        return new EmbeddingBatchSizeResolver(coreGateway, embeddingProperties);
    }

    
    @Bean
    public LlmFallbackChain llmFallbackChain(LlmProviderFactory llmProviderFactory,
                                             ProjectLlmRouter projectLlmRouter,
                                             CoreGateway coreGateway,
                                             LlmRetryPolicy llmRetryPolicy,
                                             LlmTimeoutResolver llmTimeoutResolver,
                                             TokenCounter tokenCounter,
                                             ObjectProvider<UsageCalibrationRecorder> recorderProvider) {
        return new LlmFallbackChain(llmProviderFactory, projectLlmRouter, coreGateway,
                llmRetryPolicy, llmTimeoutResolver, new PromptSizeGuard(tokenCounter),
                UsageCalibrationRecorder.from(recorderProvider));
    }
}
