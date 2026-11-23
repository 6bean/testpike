package com.sixbean.testpike.aiservice.knowledge.domain.component;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;


public class FakeEmbeddingModel implements EmbeddingModel {

    
    public static final int DIMENSION = 1536;

    
    public volatile String failOnMarker;

    private static float[] unitVector() {
        float[] v = new float[DIMENSION];
        v[0] = 1.0f;
        return v;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        String marker = failOnMarker;
        if (marker != null && instructions.stream().anyMatch(t -> t.contains(marker))) {
            throw new IllegalStateException("embedding 供应商模拟批内失败（标记=" + marker + "）");
        }
        List<Embedding> embeddings = new ArrayList<>(instructions.size());
        for (int i = 0; i < instructions.size(); i++) {
            embeddings.add(new Embedding(unitVector(), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return unitVector();
    }
}
