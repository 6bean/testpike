package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmConfigBrief;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;


class EmbeddingRouterTest {

    private EmbeddingProperties props() {
        EmbeddingProperties p = new EmbeddingProperties();
        p.setProvider("dashscope-compatible");
        p.setBaseUrl("http://localhost:18080/v1");
        p.setApiKey("sk-env");
        p.setModel("text-embedding-v3");
        p.setDimension(1024);
        return p;
    }

    private CoreGateway stubGateway(LlmConfigBrief brief, List<LlmConfigBrief> enabled) {
        return new CoreGateway("http://localhost:8083") {
            @Override
            public LlmConfigBrief getProjectEmbeddingConfig(long projectId) {
                return brief;
            }

            @Override
            public List<LlmConfigBrief> listEnabledEmbeddingConfigs() {
                return enabled;
            }
        };
    }

    private LlmConfigBrief brief(long id, String model, String baseUrl, String source, Integer dim) {
        return new LlmConfigBrief(id, "emb-" + id, "dashscope-compatible", model,
                "sk-" + id, baseUrl, source, null, null, 2, dim);
    }

    @Test
    void route_项目选用_source为project且维度透传() {
        EmbeddingRouter router = new EmbeddingRouter(
                stubGateway(brief(9L, "text-embedding-v3", "https://picked/v1", "project", 1024), List.of()),
                props());

        EmbeddingEndpointConfig cfg = router.route(10L);

        assertEquals("text-embedding-v3", cfg.modelName());
        assertEquals("sk-9", cfg.apiKey());
        assertEquals(EmbeddingEndpointConfig.SOURCE_PROJECT, cfg.source());
        assertEquals(1024, cfg.dimension(), "维度锚透传（EmbeddingDimensionGuard 输入）");
        assertEquals(9L, cfg.llmConfigId());
    }

    @Test
    void route_系统默认_source为default_emb() {
        EmbeddingRouter router = new EmbeddingRouter(
                stubGateway(brief(3L, "emb-def", "http://gw/v1", "default", 1536), List.of()), props());

        EmbeddingEndpointConfig cfg = router.route(10L);

        assertEquals(EmbeddingEndpointConfig.SOURCE_DEFAULT_EMB, cfg.source(),
                "core default 行 → embedding 域默认标识（与生成域 default 区分）");
        assertEquals(1536, cfg.dimension());
    }

    @Test
    void route_core无embedding配置_回退配置文件() {
        EmbeddingRouter router = new EmbeddingRouter(stubGateway(null, List.of()), props());

        EmbeddingEndpointConfig cfg = router.route(10L);

        assertEquals(EmbeddingEndpointConfig.SOURCE_CONFIG, cfg.source(), "三级兜底末级=.env");
        assertEquals("text-embedding-v3", cfg.modelName());
        assertEquals(1024, cfg.dimension());
    }

    @Test
    void route_core不可达_回退配置文件不抛异常() {
        CoreGateway broken = new CoreGateway("http://localhost:1") {
            @Override
            public LlmConfigBrief getProjectEmbeddingConfig(long projectId) {
                throw new IllegalStateException("core down");
            }
        };
        EmbeddingRouter router = new EmbeddingRouter(broken, props());

        EmbeddingEndpointConfig cfg = assertDoesNotThrow(() -> router.route(10L));
        assertEquals(EmbeddingEndpointConfig.SOURCE_CONFIG, cfg.source());
    }

    @Test
    void routeChain_主候选端点优先_按baseUrl去重_剔空端点() {
        EmbeddingRouter router = new EmbeddingRouter(
                stubGateway(brief(9L, "picked", "https://picked/v1", "project", 1024),
                        List.of(brief(9L, "picked", "https://picked/v1", "default", 1024),
                                brief(4L, "other", "https://other/v1", "default", 1024),
                                brief(5L, "blank", "  ", "default", 1024))),
                props());

        List<EmbeddingEndpointConfig> chain = router.routeChain(10L);

        assertEquals(2, chain.size(), "主候选端点 + 去重后次候选端点；空 baseUrl 剔除（fail-closed 判定输入）");
        assertEquals("https://picked/v1", chain.get(0).baseUrl(), "主候选端点=项目选用优先");
        assertEquals("https://other/v1", chain.get(1).baseUrl());
    }

    @Test
    void routeChain_无清单仅配置链路() {
        EmbeddingRouter router = new EmbeddingRouter(stubGateway(null, List.of()), props());

        List<EmbeddingEndpointConfig> chain = router.routeChain(10L);

        assertEquals(1, chain.size());
        assertEquals(EmbeddingEndpointConfig.SOURCE_CONFIG, chain.get(0).source());
    }
}
