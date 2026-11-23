package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmConfigBrief;
import com.sixbean.testpike.aiservice.testsupport.LogbackSink;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ProjectLlmRouterTest {

    private CoreGateway stubGateway(LlmConfigBrief brief) {
        return new CoreGateway("http://localhost:8083") {
            @Override
            public LlmConfigBrief getProjectLlmConfig(long projectId) {
                return brief;
            }

            @Override
            public String getSysConfig(String key) {
                return null;
            }
        };
    }

    @Test
    void route_项目选用_用项目模型配置() {
        LlmConfigBrief brief = new LlmConfigBrief(7L, "deepseek-main", "deepseek",
                "deepseek-chat", "sk-project", "https://api.deepseek.com/v1", "project");
        ProjectLlmRouter router = new ProjectLlmRouter(stubGateway(brief), new LlmProperties());

        LlmEndpointConfig cfg = router.route(10L);
        assertEquals("deepseek", cfg.provider());
        assertEquals("deepseek-chat", cfg.modelName());
        assertEquals("sk-project", cfg.apiKey());
        assertEquals("https://api.deepseek.com/v1", cfg.baseUrl());
        assertEquals(LlmEndpointConfig.SOURCE_PROJECT, cfg.source(), "项目选用优先（TC-K9-02）");
    }

    @Test
    void route_未选用_回退系统默认模型() {
        LlmConfigBrief brief = new LlmConfigBrief(3L, "sys-default", "openai",
                "gpt-4o", "sk-default", "http://gw/v1", "default");
        ProjectLlmRouter router = new ProjectLlmRouter(stubGateway(brief), new LlmProperties());

        LlmEndpointConfig cfg = router.route(10L);
        assertEquals("gpt-4o", cfg.modelName());
        assertEquals(LlmEndpointConfig.SOURCE_DEFAULT, cfg.source(), "系统默认（TC-K9-02）");
    }

    @Test
    void route_无任何配置_回退配置文件() {
        LlmProperties p = new LlmProperties();
        p.setProvider("deepseek");
        p.setModel("deepseek-chat");
        p.setApiKey("sk-config");
        p.setBaseUrl("http://localhost:18080/v1");
        ProjectLlmRouter router = new ProjectLlmRouter(stubGateway(null), p);

        LlmEndpointConfig cfg = router.route(10L);
        assertEquals("deepseek", cfg.provider());
        assertEquals("deepseek-chat", cfg.modelName());
        assertEquals("sk-config", cfg.apiKey());
        assertEquals(LlmEndpointConfig.SOURCE_CONFIG, cfg.source(), "无清单回退配置文件");
    }

    @Test
    void route_core不可达_回退配置文件_不抛异常() {
        LlmProperties p = new LlmProperties();
        p.setModel("fallback-model");
        CoreGateway broken = new CoreGateway("http://localhost:1") {
            @Override
            public LlmConfigBrief getProjectLlmConfig(long projectId) {
                throw new RuntimeException("core 不可达");
            }
        };
        ProjectLlmRouter router = new ProjectLlmRouter(broken, p);

        LlmEndpointConfig cfg = router.route(10L);
        assertEquals("fallback-model", cfg.modelName());
        assertEquals(LlmEndpointConfig.SOURCE_CONFIG, cfg.source(), "降级路径如实回退不静默");
    }


    @Test
    void route_无清单_K8配置表默认端点() {
        CoreGateway gw = new CoreGateway("http://localhost:8083") {
            @Override
            public LlmConfigBrief getProjectLlmConfig(long projectId) {
                return null;
            }

            @Override
            public String getSysConfig(String key) {
                return switch (key) {
                    case "llm.provider" -> "openai";
                    case "llm.base-url" -> "http://cfg-gw/v1";
                    case "llm.model" -> "gpt-4o-cfg";
                    case "llm.api-key" -> "sk-cfg";
                    default -> null;
                };
            }
        };
        ProjectLlmRouter router = new ProjectLlmRouter(gw, new LlmProperties());

        LlmEndpointConfig cfg = router.route(10L);
        assertEquals("openai", cfg.provider());
        assertEquals("gpt-4o-cfg", cfg.modelName());
        assertEquals("sk-cfg", cfg.apiKey(), "密钥来自配置表（T56 密钥 .env→配置表）");
        assertEquals("http://cfg-gw/v1", cfg.baseUrl());
        assertEquals(LlmEndpointConfig.SOURCE_CONFIG_DB, cfg.source(), "无清单优先 K8 配置表");
    }

    @Test
    void route_K8配置表有端点无apiKey_回退env密钥() {
        CoreGateway gw = new CoreGateway("http://localhost:8083") {
            @Override
            public LlmConfigBrief getProjectLlmConfig(long projectId) {
                return null;
            }

            @Override
            public String getSysConfig(String key) {
                return switch (key) {
                    case "llm.provider" -> "deepseek";
                    case "llm.base-url" -> "http://cfg-gw/v1";
                    case "llm.model" -> "deepseek-chat";
                    default -> null;
                };
            }
        };
        LlmProperties p = new LlmProperties();
        p.setApiKey("sk-env");
        ProjectLlmRouter router = new ProjectLlmRouter(gw, p);

        LlmEndpointConfig cfg = router.route(10L);
        assertEquals("sk-env", cfg.apiKey(), "配置表未设密钥 → .env 兜底");
        assertEquals(LlmEndpointConfig.SOURCE_CONFIG_DB, cfg.source());
    }

    @Test
    void route_K8配置全空_回退配置文件() {
        LlmProperties p = new LlmProperties();
        p.setProvider("deepseek");
        p.setModel("deepseek-chat");
        p.setApiKey("sk-config");
        p.setBaseUrl("http://localhost:18080/v1");
        ProjectLlmRouter router = new ProjectLlmRouter(stubGateway(null), p);

        LlmEndpointConfig cfg = router.route(10L);
        assertEquals(LlmEndpointConfig.SOURCE_CONFIG, cfg.source(), "K8 配置全空 → .env 配置文件兜底");
        assertEquals("sk-config", cfg.apiKey());
    }

    @Test
    void route_无清单且无K8配置_回退文案点名配置入口与参数名() {
        // R32 honest-pointer: whenever routing degrades to the shipped config
        // file, the operator must be told where to fix it (menu path literal +
        // config key literals) instead of seeing a silent fallback.
        LlmProperties p = new LlmProperties();
        p.setModel("deepseek-chat");
        ProjectLlmRouter router = new ProjectLlmRouter(stubGateway(null), p);

        try (LogbackSink sink = LogbackSink.attach(ProjectLlmRouter.class)) {
            router.route(10L);
            String line = sink.infosContaining("回退配置文件").stream()
                    .findFirst().orElse("<missing>");
            assertTrue(line.contains("系统管理") && line.contains("大模型配置")
                            && line.contains("系统配置") && line.contains("llm.provider"),
                    "回退文案必须点名菜单路径与参数名称（R32）: " + line);
        }
    }
}
