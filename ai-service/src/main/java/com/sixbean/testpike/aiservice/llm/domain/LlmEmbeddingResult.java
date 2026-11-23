package com.sixbean.testpike.aiservice.llm.domain;

import java.util.List;


public record LlmEmbeddingResult(
        List<float[]> embeddings,
        String provider,
        String model,
        int totalTokens,
        long latencyMs) {
}
