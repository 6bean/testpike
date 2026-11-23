package com.sixbean.testpike.aiservice.gen.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.EmbeddingCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmEmbeddingResult;
import com.sixbean.testpike.aiservice.llm.domain.component.EmbeddingFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.EmbeddingProperties;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.ArrayList;
import java.util.List;

/**
 * Semantic text reconciliation over embeddings : one
 * batched round-trip embeds {@code items + [aggregateText]} through
 * {@link EmbeddingFallbackChain} — the only embedding outbound in this
 * service (mock mode / unconfigured chain fail closed with a human-readable
 * {@link ServiceException}, never a fabricated vector) — and answers the
 * cosine similarity of every item against the aggregate text.
 * <p>This class is the repository's ONLY in-memory cosine implementation
 * and is not reusable against request-time text, and a second copy elsewhere
 * would drift. A size or dimension mismatch is a provider-contract breach —
 * fail closed, the caller decides the degradation.
 */
public class SemanticMatcher {

    private final EmbeddingFallbackChain chain;
    private final EmbeddingProperties properties;

    public SemanticMatcher(EmbeddingFallbackChain chain, EmbeddingProperties properties) {
        this.chain = chain;
        this.properties = properties;
    }

    /**
     * Cosine similarity of each item against the aggregate text, in input order.
     *
     * @param items         criteria items (non-empty; every entry embeds as-is)
     * @param aggregateText the whole text they are reconciled against, embedded
     *                      as the LAST input of the same batch
     * @param projectId     routing anchor of the embedding chain (cost/model selection)
     */
    public List<Double> similarities(List<String> items, String aggregateText, long projectId) {
        List<String> inputs = new ArrayList<>(items);
        inputs.add(aggregateText);
        LlmEmbeddingResult result = chain.embed(
                EmbeddingCompletionRequest.of(properties.getModel(), inputs), projectId);
        List<float[]> vectors = result.embeddings();
        if (vectors.size() != inputs.size()) {
            throw new ServiceException("embedding 返回数量与请求条目不一致（返回 " + vectors.size()
                    + " 条，请求 " + inputs.size() + " 条），语义对账本轮不可用",
                    BizErrorCode.AI_PROVIDER_ERROR);
        }
        float[] aggregate = vectors.get(vectors.size() - 1);
        List<Double> similarities = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            similarities.add(cosine(vectors.get(i), aggregate));
        }
        return similarities;
    }

    /** Cosine in [-1, 1]; a zero-length vector carries no direction → similarity 0. */
    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new ServiceException("embedding 向量维度不一致（" + a.length + "≠" + b.length
                    + "），语义对账本轮不可用", BizErrorCode.AI_PROVIDER_ERROR);
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
