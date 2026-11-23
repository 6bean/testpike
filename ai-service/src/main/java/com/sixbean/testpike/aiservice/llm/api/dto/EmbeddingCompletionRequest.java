package com.sixbean.testpike.aiservice.llm.api.dto;

import java.util.List;


public record EmbeddingCompletionRequest(String model, List<String> input) {

    public static EmbeddingCompletionRequest of(String model, List<String> input) {
        return new EmbeddingCompletionRequest(model, input);
    }
}
