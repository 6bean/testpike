package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.EmbeddingCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmEmbeddingResult;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.ListPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;


@Component
public class EmbeddingFallbackChain {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingFallbackChain.class);

    private final LlmProviderFactory factory;
    private final EmbeddingRouter router;
    private final LlmRetryPolicy retryPolicy;
    private final LlmTimeoutResolver timeoutResolver;
    private final EmbeddingDimensionGuard dimensionGuard;
    private final EmbeddingBatchSizeResolver batchSizeResolver;

    @org.springframework.beans.factory.annotation.Autowired
    public EmbeddingFallbackChain(LlmProviderFactory factory, EmbeddingRouter router,
                                  LlmRetryPolicy retryPolicy, LlmTimeoutResolver timeoutResolver,
                                  EmbeddingDimensionGuard dimensionGuard,
                                  EmbeddingBatchSizeResolver batchSizeResolver) {
        this.factory = factory;
        this.router = router;
        this.retryPolicy = retryPolicy;
        this.timeoutResolver = timeoutResolver;
        this.dimensionGuard = dimensionGuard;
        this.batchSizeResolver = batchSizeResolver;
    }

    /** Compatibility wiring without a K8 resolver: provider cap = config conservative default. */
    public EmbeddingFallbackChain(LlmProviderFactory factory, EmbeddingRouter router,
                                  LlmRetryPolicy retryPolicy, LlmTimeoutResolver timeoutResolver,
                                  EmbeddingDimensionGuard dimensionGuard) {
        this(factory, router, retryPolicy, timeoutResolver, dimensionGuard,
                new EmbeddingBatchSizeResolver(null, new EmbeddingProperties()));
    }

    
    public LlmEmbeddingResult embed(EmbeddingCompletionRequest request, long projectId) {
        if (factory.isMock()) {
            throw new ServiceException(
                    "embedding 服务不可用：当前为 mock 替身模式，请配置真实 embedding 供应商"
                            + "（llm.mock-enabled=false + 大模型清单启用 embedding 模型）",
                    BizErrorCode.AI_PROVIDER_ERROR);
        }
        List<EmbeddingEndpointConfig> chain = router.routeChain(projectId);
        if (chain.isEmpty()) {
            throw new ServiceException("未配置可用 embedding 供应商：请在系统设置-大模型清单"
                    + "启用至少一个 embedding 类型模型（或配置 llm.embedding.base-url）",
                    BizErrorCode.AI_PROVIDER_ERROR);
        }
        ServiceException last = null;
        for (EmbeddingEndpointConfig endpoint : chain) {
            // Provider-side input cap (defect ledger: un-clamped K8 batch 128 vs
            // dashscope-compatible hard limit 10 rejected every large-document
            // ingest). Model-row value wins; otherwise K8 hot-tunable key with the
            // conservative config default. Never below 1 whatever the config says.
            int cap = Math.max(1, endpoint.batchCap() != null
                    ? endpoint.batchCap() : batchSizeResolver.resolveProviderCap());
            try {
                if (request.input().size() <= cap) {
                    // within-cap fast path = byte-identical to the pre-cap behavior
                    EmbeddingCompletionRequest endpointRequest =
                            EmbeddingCompletionRequest.of(endpoint.modelName(), request.input());
                    LlmEmbeddingResult result = retryPolicy.executeGeneric(() ->
                            factory.embedFor(endpointRequest, endpoint, timeoutResolver.resolve()));
                    dimensionGuard.check(result, endpoint.dimension());
                    return result;
                }
                // over-cap = split into per-request segments on this endpoint's cap,
                // each segment keeps its own retry budget and dimension fail-fast;
                // merged result preserves input order (batch-alignment precondition).
                List<float[]> mergedVectors = new ArrayList<>();
                String mergedProvider = null;
                String mergedModel = null;
                int mergedTokens = 0;
                long mergedLatency = 0L;
                for (List<String> segment : ListPartition.of(request.input(), cap)) {
                    EmbeddingCompletionRequest segmentRequest =
                            EmbeddingCompletionRequest.of(endpoint.modelName(), segment);
                    LlmEmbeddingResult segmentResult = retryPolicy.executeGeneric(() ->
                            factory.embedFor(segmentRequest, endpoint, timeoutResolver.resolve()));
                    dimensionGuard.check(segmentResult, endpoint.dimension());
                    mergedVectors.addAll(segmentResult.embeddings());
                    if (mergedProvider == null) {
                        mergedProvider = segmentResult.provider();
                        mergedModel = segmentResult.model();
                    }
                    mergedTokens += segmentResult.totalTokens();
                    mergedLatency += segmentResult.latencyMs();
                }
                return new LlmEmbeddingResult(mergedVectors, mergedProvider, mergedModel,
                        mergedTokens, mergedLatency);
            } catch (ServiceException e) {
                last = e;
                log.warn("embedding provider failed projectId={} provider={} err={} (falling back to next provider)",
                        projectId, endpoint.provider(), e.getMessage());
            }
        }
        throw last;
    }
}
