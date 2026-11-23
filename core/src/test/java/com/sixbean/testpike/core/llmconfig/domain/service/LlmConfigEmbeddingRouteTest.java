package com.sixbean.testpike.core.llmconfig.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigBrief;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInfo;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import com.sixbean.testpike.core.support.LlmConfigSharedDbFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class LlmConfigEmbeddingRouteTest {

    @Autowired
    private LlmConfigService llmConfigService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // R37: no TRUNCATE on the shared dev DB — factory embedding default row
        // stays alive; only this fixture's own residue is removed.
        LlmConfigSharedDbFixture.cleanPreservingFactory(jdbcTemplate, List.of("EMBROUTE"));
    }

    private long createProject() {
        return projectService.create(5L, new com.sixbean.testpike.core.project.api.dto.ProjectCreateInput(
                "Emb路由项目", "EMBROUTE", "zh-CN", "P1", null));
    }

    private LlmConfigInput genInput(String name, int isDefault) {
        return new LlmConfigInput(name, "deepseek", "deepseek-chat", "sk-gen",
                "https://api.deepseek.com/v1", 1, isDefault, "生成模型");
    }

    private LlmConfigInput embInput(String name, Integer dimension, int isDefault) {
        return new LlmConfigInput(name, "dashscope-compatible", "text-embedding-v3", "sk-emb-1",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", 1, isDefault,
                "embedding 路由探针", null, null, 2, dimension);
    }


    @Test
    void resolveProjectEmbeddingBrief_选用优先() {
        LlmConfigInfo emb = llmConfigService.create(1L, embInput("emb-picked", 1024, 0));
        long projectId = createProject();
        jdbcTemplate.update("UPDATE tp_project SET embedding_config_id = ? WHERE id = ?",
                emb.id(), projectId);

        LlmConfigBrief brief = llmConfigService.resolveProjectEmbeddingBrief(projectId);

        assertEquals(emb.id(), brief.id(), "项目选用 embedding 优先");
        assertEquals(LlmConfigBrief.SOURCE_PROJECT, brief.source());
        assertEquals(1024, brief.embeddingDimension(), "维度锚透传（guard 输入）");
        assertEquals("sk-emb-1", brief.apiKey(), "internal 解密明文（禁日志在消费端守）");
    }

    @Test
    void resolveProjectEmbeddingBrief_未选用回退type2默认() {
        long projectId = createProject();
        llmConfigService.create(1L, embInput("emb-not-default", 2048, 0));
        LlmConfigInfo embDefault = llmConfigService.create(1L, embInput("emb-default", 1536, 1));

        LlmConfigBrief brief = llmConfigService.resolveProjectEmbeddingBrief(projectId);

        assertEquals(embDefault.id(), brief.id(), "未选用 → embedding 域默认");
        assertEquals(LlmConfigBrief.SOURCE_DEFAULT, brief.source());
        assertEquals(1536, brief.embeddingDimension());
    }

    @Test
    void resolveProjectEmbeddingBrief_生成默认不被embedding回退命中() {
        long projectId = createProject();
        // IT-EMB-02 reverse law: a generation default row (type=1) must never
        // be returned by the embedding fallback. With R37 the factory embedding
        // default stays resident on the shared DB, so the "no config at all ->
        // null" tier is no longer constructible there (never clear factory rows
        // for tests); that tier's semantics are covered by the ai-service .env
        // fallback chain and LlmConfigEmbeddingSeedInitializerTest's anchor.
        LlmConfigInfo gen = llmConfigService.create(1L, genInput("gen-default", 1));

        LlmConfigBrief brief = llmConfigService.resolveProjectEmbeddingBrief(projectId);

        assertNotNull(brief, "出厂向量默认行在册 → 回退命中 embedding 域");
        assertNotEquals(gen.id(), brief.id(), "生成默认行零进入 embedding 回退（分域铁律）");
        assertEquals(LlmConfigBrief.SOURCE_DEFAULT, brief.source());
        assertEquals(1536, brief.embeddingDimension(), "命中行=出厂 1536 维向量默认");
    }


    @Test
    void listEnabledBriefs_按类型分域_默认优先序() {
        // Baseline-aware: the generation install-default row seeded by
        // tools/verification/seed_slot_llm_default.py is resident shared baseline (R37);
        // park it around this body instead of deleting it, then restore in finally.
        LlmConfigSharedDbFixture.withParkedGenerationInstallDefaults(jdbcTemplate, () -> {
            llmConfigService.create(1L, embInput("emb-a", 1024, 0));
            LlmConfigInfo embDefault = llmConfigService.create(1L, embInput("emb-b", 1024, 1));
            LlmConfigInfo genDefault = llmConfigService.create(1L, genInput("gen-x", 1));

            List<LlmConfigBrief> embs = llmConfigService.listEnabledBriefs(2);
            assertEquals(2 + LlmConfigSharedDbFixture.factoryRowCount(jdbcTemplate), embs.size(),
                    "modelType=2 仅 embedding 行（R37：出厂向量默认行常驻并存）");
            assertEquals(embDefault.id(), embs.get(0).id(), "系统默认优先（降级链序）");
            assertTrue(embs.stream().allMatch(b -> b.modelType() == 2));

            List<LlmConfigBrief> gens = llmConfigService.listEnabledBriefs(1);
            assertEquals(1, gens.size(), "modelType=1 仅生成行");
            assertEquals(genDefault.id(), gens.get(0).id());

            assertEquals(3 + LlmConfigSharedDbFixture.factoryRowCount(jdbcTemplate),
                    llmConfigService.listEnabledBriefs(null).size(), "null=全集（internal 显式）");
            });
    }

    @Test
    void listEnabledBriefs_无参委托生成域_存量零漂移() {
        // Baseline-aware: the generation install-default row seeded by
        // tools/verification/seed_slot_llm_default.py is resident shared baseline (R37);
        // park it around this body instead of deleting it, then restore in finally.
        LlmConfigSharedDbFixture.withParkedGenerationInstallDefaults(jdbcTemplate, () -> {
            llmConfigService.create(1L, embInput("emb-only", 1024, 1));
            assertTrue(llmConfigService.listEnabledBriefs().isEmpty(),
                    "无参=生成域降级链，embedding 默认行零进入（D0 零漂移）");
            });
    }
}
