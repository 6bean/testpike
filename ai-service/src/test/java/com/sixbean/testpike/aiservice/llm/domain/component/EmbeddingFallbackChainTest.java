package com.sixbean.testpike.aiservice.llm.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.EmbeddingCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmEmbeddingResult;
import com.sixbean.testpike.aiservice.llm.domain.service.impl.MockLlmProvider;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class EmbeddingFallbackChainTest {

    private final EmbeddingDimensionGuard guard = new EmbeddingDimensionGuard();

    private EmbeddingEndpointConfig endpoint(long id, String baseUrl, int dimension) {
        return new EmbeddingEndpointConfig("dashscope-compatible", "emb-" + id, "sk-" + id,
                baseUrl, EmbeddingEndpointConfig.SOURCE_PROJECT, dimension, id);
    }

    private LlmEmbeddingResult vectors(int dimension) {
        return new LlmEmbeddingResult(List.of(new float[dimension]), "dashscope-compatible",
                "text-embedding-v3", 5, 10L);
    }

    
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

    private EmbeddingFallbackChain chain(LlmProviderFactory factory, List<EmbeddingEndpointConfig> endpoints,
                                         LlmRetryPolicy retryPolicy) {
        return new EmbeddingFallbackChain(factory, stubRouter(endpoints), retryPolicy,
                new LlmTimeoutResolver(null, new LlmProperties()), guard);
    }

    @Test
    void 空链_failclosed_50201人话点名配置路径() {
        LlmProviderFactory factory = new LlmProviderFactory(
                new MockLlmProvider("m"), new LlmProperties(), new ObjectMapper()) {
            @Override
            public boolean isMock() {
                return false;
            }
        };
        ServiceException ex = assertThrows(ServiceException.class, () -> chain(factory, List.of(),
                new LlmRetryPolicy(1, 1, 2))
                .embed(EmbeddingCompletionRequest.of("text-embedding-v3", List.of("文本")), 1L));
        assertEquals(BizErrorCode.AI_PROVIDER_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("embedding"), "人话须点名 embedding：" + ex.getMessage());
    }

    
    @Test
    void 首位候选端点模型名锚路由端点_不残留调用方缺省名() {
        List<String> sentModels = new ArrayList<>();
        LlmProviderFactory factory = new LlmProviderFactory(
                new MockLlmProvider("m"), new LlmProperties(), new ObjectMapper()) {
            @Override
            public boolean isMock() {
                return false;
            }

            @Override
            public LlmEmbeddingResult embedFor(EmbeddingCompletionRequest request,
                                               EmbeddingEndpointConfig endpoint, Duration timeout) {
                sentModels.add(request.model());
                return "text-embedding-v4".equals(request.model())
                        ? vectors(endpoint.dimension()) : vectors(1024);
            }
        };
        LlmEmbeddingResult result = chain(factory,
                List.of(new EmbeddingEndpointConfig("openai-compatible", "text-embedding-v4",
                        "sk-x", "http://localhost:4000/v1",
                        EmbeddingEndpointConfig.SOURCE_DEFAULT_EMB, 1536, 1196L)),
                new LlmRetryPolicy(1, 1, 2))
                .embed(EmbeddingCompletionRequest.of("text-embedding-v3", List.of("文本")), 0L);
        assertEquals(List.of("text-embedding-v4"), sentModels,
                "首位候选端点请求模型名=路由端点自有模型名（清单权威），禁残留调用方缺省 v3");
        assertEquals(1536, result.embeddings().get(0).length, "维度 guard 放行=入库可行");
    }

    @Test
    void 首位候选端点失败_降级次候选端点成功() {
        AtomicInteger calls = new AtomicInteger();
        LlmProviderFactory factory = new LlmProviderFactory(
                new MockLlmProvider("m"), new LlmProperties(), new ObjectMapper()) {
            @Override
            public boolean isMock() {
                return false;
            }

            @Override
            public LlmEmbeddingResult embedFor(EmbeddingCompletionRequest request,
                                               EmbeddingEndpointConfig endpoint, Duration timeout) {
                if (calls.incrementAndGet() == 1) {
                    throw new ServiceException("AI 供应商调用失败: 503", BizErrorCode.AI_PROVIDER_ERROR);
                }
                return vectors(endpoint.dimension());
            }
        };
        LlmEmbeddingResult result = chain(factory,
                List.of(endpoint(1L, "https://a/v1", 3), endpoint(2L, "https://b/v1", 3)),
                new LlmRetryPolicy(1, 1, 2)).embed(
                        EmbeddingCompletionRequest.of("emb-1", List.of("文本")), 1L);
        assertEquals(2, calls.get(), "首位候选端点 1 次 + 次候选端点 1 次（retry=1 不重掷）");
        assertEquals(3, result.embeddings().get(0).length);
    }

    @Test
    void 重试耗尽_换链路次数含退避基数() {
        AtomicInteger calls = new AtomicInteger();
        LlmProviderFactory factory = new LlmProviderFactory(
                new MockLlmProvider("m"), new LlmProperties(), new ObjectMapper()) {
            @Override
            public boolean isMock() {
                return false;
            }

            @Override
            public LlmEmbeddingResult embedFor(EmbeddingCompletionRequest request,
                                               EmbeddingEndpointConfig endpoint, Duration timeout) {
                calls.incrementAndGet();
                throw new ServiceException("模型调用超时", BizErrorCode.AI_TIMEOUT);
            }
        };
        ServiceException ex = assertThrows(ServiceException.class, () -> chain(factory,
                List.of(endpoint(1L, "https://a/v1", 3), endpoint(2L, "https://b/v1", 3)),
                new LlmRetryPolicy(2, 1, 2)).embed(
                        EmbeddingCompletionRequest.of("emb-1", List.of("文本")), 1L));
        assertEquals(4, calls.get(), "逐链路重试耗尽后降级（IT-EMB-05 退避≤3 语义的小基数复现）");
        assertEquals(BizErrorCode.AI_TIMEOUT, ex.getCode(), "保留最后一次错误码");
    }

    @Test
    void mock模式_直抛人话禁假向量() {
        AtomicInteger calls = new AtomicInteger();
        LlmProviderFactory mockFactory = new LlmProviderFactory(
                new MockLlmProvider("m"), new LlmProperties(), new ObjectMapper()) {
            @Override
            public LlmEmbeddingResult embedFor(EmbeddingCompletionRequest request,
                                               EmbeddingEndpointConfig endpoint, Duration timeout) {
                calls.incrementAndGet();
                throw new ServiceException("embedding 服务不可用：当前为 mock 替身模式，"
                        + "请配置真实 embedding 供应商（llm.mock-enabled=false）",
                        BizErrorCode.AI_PROVIDER_ERROR);
            }
        };
        assertTrue(mockFactory.isMock());
        ServiceException ex = assertThrows(ServiceException.class, () -> chain(mockFactory,
                List.of(endpoint(1L, "https://a/v1", 3)), new LlmRetryPolicy(3, 1, 2))
                .embed(EmbeddingCompletionRequest.of("emb", List.of("文本")), 1L));
        assertEquals(BizErrorCode.AI_PROVIDER_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("mock"), "人话点名 mock 替身：" + ex.getMessage());
        assertEquals(0, calls.get(), "mock 直抛不进重试/降级链（禁假向量禁空烧）");
    }

    @Test
    void 维度不符_guard拦截_不入库() {
        LlmProviderFactory factory = new LlmProviderFactory(
                new MockLlmProvider("m"), new LlmProperties(), new ObjectMapper()) {
            @Override
            public boolean isMock() {
                return false;
            }

            @Override
            public LlmEmbeddingResult embedFor(EmbeddingCompletionRequest request,
                                               EmbeddingEndpointConfig endpoint, Duration timeout) {
                return vectors(8);
            }
        };
        ServiceException ex = assertThrows(ServiceException.class, () -> chain(factory,
                List.of(endpoint(1L, "https://a/v1", 3)), new LlmRetryPolicy(1, 1, 2))
                .embed(EmbeddingCompletionRequest.of("emb", List.of("文本")), 1L));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(),
                "维度 fail-fast（IT-VEC-01 锚在降级链出口统一守）");
        assertTrue(ex.getMessage().contains("维度"));
    }
}
