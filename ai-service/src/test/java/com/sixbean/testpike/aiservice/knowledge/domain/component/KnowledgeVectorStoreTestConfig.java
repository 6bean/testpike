package com.sixbean.testpike.aiservice.knowledge.domain.component;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * {@link com.sixbean.testpike.aiservice.llm.domain.component.EmbeddingModelAdapter}
 *
 */
@TestConfiguration
public class KnowledgeVectorStoreTestConfig {

    @Bean
    @Primary
    public EmbeddingModel fakeEmbeddingModel() {
        return new FakeEmbeddingModel();
    }
}
