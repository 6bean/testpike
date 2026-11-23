package com.sixbean.testpike.aiservice.knowledge.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.EmbeddingCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmEmbeddingResult;
import com.sixbean.testpike.aiservice.llm.domain.component.EmbeddingFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.EmbeddingProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;


@Component
public class EmbeddingModelAdapter implements EmbeddingModel {

    private final EmbeddingFallbackChain chain;
    private final EmbeddingProperties properties;

    public EmbeddingModelAdapter(EmbeddingFallbackChain chain, EmbeddingProperties properties) {
        this.chain = chain;
        this.properties = properties;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        LlmEmbeddingResult result = chain.embed(
                EmbeddingCompletionRequest.of(properties.getModel(), request.getInstructions()),
                GLOBAL_SCOPE_PROJECT_ID);
        List<float[]> vectors = result.embeddings();
        List<Embedding> embeddings = new ArrayList<>(vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
            embeddings.add(new Embedding(vectors.get(i), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public int dimensions() {
        return properties.getDimension();
    }

    
    private static final long GLOBAL_SCOPE_PROJECT_ID = 0L;
}
