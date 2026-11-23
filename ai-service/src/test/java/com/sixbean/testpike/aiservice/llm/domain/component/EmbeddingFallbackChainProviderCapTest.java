package com.sixbean.testpike.aiservice.llm.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.EmbeddingCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmEmbeddingResult;
import com.sixbean.testpike.aiservice.llm.domain.service.impl.MockLlmProvider;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Provider-side input-cap clamping of the embedding fallback chain
 * (defect ledger: dashscope-compatible text-embedding hard-limits a single request
 * to 10 inputs while K8 ai.embedding.batch-size=128, so every large-document ingest
 * request was rejected 400 and the whole ingest was lost).
 * Bidirectional fixture: over-cap input is split per endpoint cap and order is
 * preserved; within-cap input stays byte-identical (single call, no re-wrapping).
 * L2 provider HTTP side uses the code-level stub (same convention as
 * {@link EmbeddingFallbackChainTest}); real-provider registration belongs to L4.
 */
class EmbeddingFallbackChainProviderCapTest {

    private final EmbeddingDimensionGuard guard = new EmbeddingDimensionGuard();

    private EmbeddingEndpointConfig endpoint(String provider, long id, Integer batchCap) {
        return new EmbeddingEndpointConfig(provider, "emb-" + id, "sk-" + id,
                "http://provider-" + id + "/v1", EmbeddingEndpointConfig.SOURCE_PROJECT,
                4, id, batchCap);
    }

    /** Routed-chain stub: the fixed endpoint list is the whole degradation chain. */
    private EmbeddingRouter stubRouter(List<EmbeddingEndpointConfig> chain) {
        return new EmbeddingRouter(new CoreGateway("http://localhost:8083"), new EmbeddingProperties()) {
            @Override
            public List<EmbeddingEndpointConfig> routeChain(long projectId) {
                return chain;
            }

            @Override
            public EmbeddingEndpointConfig route(long projectId) {
                return chain.isEmpty() ? null : chain.get(0);
            }
        };
    }

    /**
     * Stub provider: echoes each input's numeric marker as vector[0], so merged
     * order is observable end to end; records the per-request input sizes.
     */
    private LlmProviderFactory recordingFactory(List<Integer> sentSizes,
                                                List<List<String>> sentInputs,
                                                Map<Integer, Integer> wrongDimSegment) {
        return new LlmProviderFactory(new MockLlmProvider("m"), new LlmProperties(), new ObjectMapper()) {
            @Override
            public boolean isMock() {
                return false;
            }

            @Override
            public LlmEmbeddingResult embedFor(EmbeddingCompletionRequest request,
                                               EmbeddingEndpointConfig endpoint, Duration timeout) {
                sentSizes.add(request.input().size());
                sentInputs.add(List.copyOf(request.input()));
                int segment = sentSizes.size();
                int dimension = wrongDimSegment.getOrDefault(segment, 4);
                List<float[]> vectors = new ArrayList<>();
                for (String text : request.input()) {
                    float[] vector = new float[dimension];
                    vector[0] = Float.parseFloat(text.substring(1));
                    vectors.add(vector);
                }
                return new LlmEmbeddingResult(vectors, endpoint.provider(), endpoint.modelName(),
                        request.input().size(), 5L);
            }
        };
    }

    private EmbeddingFallbackChain chain(LlmProviderFactory factory,
                                         List<EmbeddingEndpointConfig> endpoints,
                                         EmbeddingBatchSizeResolver resolver) {
        return new EmbeddingFallbackChain(factory, stubRouter(endpoints),
                new LlmRetryPolicy(1, 1, 2),
                new LlmTimeoutResolver(null, new LlmProperties()), guard, resolver);
    }

    private static List<String> inputs(int count) {
        List<String> texts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            texts.add("s" + i);
        }
        return texts;
    }

    /** A static CoreGateway substitute that answers only the provider-cap key. */
    private static EmbeddingBatchSizeResolver resolverWithK8(String providerCapValue,
                                                             EmbeddingProperties properties) {
        CoreGateway gateway = new CoreGateway("http://localhost:8083") {
            @Override
            public String getSysConfig(String key) {
                return EmbeddingBatchSizeResolver.KEY_PROVIDER_BATCH_CAP.equals(key)
                        ? providerCapValue : null;
            }
        };
        return new EmbeddingBatchSizeResolver(gateway, properties);
    }

    @Test
    void 超限输入按端点上限拆分_向量序与用量守恒() {
        List<Integer> sentSizes = new ArrayList<>();
        List<List<String>> sentInputs = new ArrayList<>();
        LlmProviderFactory factory = recordingFactory(sentSizes, sentInputs, Map.of());

        LlmEmbeddingResult result = chain(factory,
                List.of(endpoint("dashscope-compatible", 3570L, 10)),
                resolverWithK8(null, new EmbeddingProperties()))
                .embed(EmbeddingCompletionRequest.of("caller-default-model", inputs(25)), 0L);

        assertEquals(List.of(10, 10, 5), sentSizes, "25 条输入在 cap=10 端点上须拆为 10/10/5 三请求");
        List<Float> order = new ArrayList<>();
        for (float[] vector : result.embeddings()) {
            order.add(vector[0]);
        }
        List<Float> expected = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            expected.add((float) i);
        }
        assertEquals(expected, order, "分段结果拼接后序=输入序（批量对齐前提不破坏）");
        assertEquals(25, result.totalTokens(), "token 用量=各分段求和，禁只取末段");
        assertEquals(15L, result.latencyMs(), "耗时=各分段求和");
        assertEquals("dashscope-compatible", result.provider());
    }

    @Test
    void 上限内批次单发零漂移() {
        List<Integer> sentSizes = new ArrayList<>();
        List<List<String>> sentInputs = new ArrayList<>();
        LlmProviderFactory factory = recordingFactory(sentSizes, sentInputs, Map.of());

        List<String> input = inputs(8);
        LlmEmbeddingResult result = chain(factory,
                List.of(endpoint("dashscope-compatible", 3570L, 10)),
                resolverWithK8(null, new EmbeddingProperties()))
                .embed(EmbeddingCompletionRequest.of("caller-default-model", input), 0L);

        assertEquals(List.of(8), sentSizes, "cap 内=单请求原样发出，零拆分零漂移");
        assertEquals(List.of(input), sentInputs, "请求 input 引用集与顺序原样");
        assertEquals(8, result.embeddings().size());
    }

    @Test
    void 端点无上限值时取保守缺省10() {
        List<Integer> sentSizes = new ArrayList<>();
        List<List<String>> sentInputs = new ArrayList<>();
        LlmProviderFactory factory = recordingFactory(sentSizes, sentInputs, Map.of());
        // 7-arg endpoint = batchCap absent (pre-cap core payload), config default = 10
        EmbeddingEndpointConfig noCap = new EmbeddingEndpointConfig("dashscope-compatible",
                "emb-3570", "sk-3570", "http://provider-3570/v1",
                EmbeddingEndpointConfig.SOURCE_PROJECT, 4, 3570L);

        chain(factory, List.of(noCap), new EmbeddingBatchSizeResolver(null, new EmbeddingProperties()))
                .embed(EmbeddingCompletionRequest.of("m", inputs(12)), 0L);

        assertEquals(List.of(10, 2), sentSizes, "无行级 cap=保守缺省 10 钳制（12→10/2）");
    }

    @Test
    void K8热更键覆写保守缺省() {
        List<Integer> sentSizes = new ArrayList<>();
        List<List<String>> sentInputs = new ArrayList<>();
        LlmProviderFactory factory = recordingFactory(sentSizes, sentInputs, Map.of());

        chain(factory,
                List.of(endpoint("openai", 1L, null)),
                resolverWithK8("4", new EmbeddingProperties()))
                .embed(EmbeddingCompletionRequest.of("m", inputs(9)), 0L);

        assertEquals(List.of(4, 4, 1), sentSizes, "K8 ai.embedding.provider-batch-cap=4 即下一请求生效");
    }

    @Test
    void 拆分后某段维度不符仍逐段fail_fast() {
        List<Integer> sentSizes = new ArrayList<>();
        List<List<String>> sentInputs = new ArrayList<>();
        // segment 2 answers with 3-dim vectors while the endpoint anchor is 4
        LlmProviderFactory factory = recordingFactory(sentSizes, sentInputs, Map.of(2, 3));

        assertThrows(ServiceException.class, () -> chain(factory,
                List.of(endpoint("dashscope-compatible", 3570L, 10)),
                resolverWithK8(null, new EmbeddingProperties()))
                .embed(EmbeddingCompletionRequest.of("m", inputs(12)), 0L));

        assertEquals(List.of(10, 2), sentSizes, "第二段维度违规即断链，禁静默入库错维向量");
    }
}
