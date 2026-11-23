package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;


class EmbeddingBatchSizeResolverTest {

    private EmbeddingBatchSizeResolver resolver(Map<String, String> k8, int configFallback) {
        CoreGateway stub = new CoreGateway("http://localhost:8083") {
            @Override
            public String getSysConfig(String key) {
                return k8.get(key);
            }
        };
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setBatchSize(configFallback);
        return new EmbeddingBatchSizeResolver(stub, properties);
    }

    @Test
    void resolve_K8合法值生效() {
        assertEquals(64, resolver(Map.of("ai.embedding.batch-size", "64"), 128).resolve());
    }

    @Test
    void resolve_K8非法整数_回退配置() {
        assertEquals(128, resolver(Map.of("ai.embedding.batch-size", "abc"), 128).resolve());
    }

    @Test
    void resolve_K8越界_回退配置() {
        assertEquals(96, resolver(Map.of("ai.embedding.batch-size", "0"), 96).resolve());
        assertEquals(96, resolver(Map.of("ai.embedding.batch-size", "1025"), 96).resolve());
    }

    @Test
    void resolve_K8不可达_回退配置() {
        assertEquals(200, resolver(Map.of(), 200).resolve());
    }

    @Test
    void resolve_边界值1与1024生效() {
        assertEquals(1, resolver(Map.of("ai.embedding.batch-size", "1"), 128).resolve());
        assertEquals(1024, resolver(Map.of("ai.embedding.batch-size", "1024"), 128).resolve());
    }
}
