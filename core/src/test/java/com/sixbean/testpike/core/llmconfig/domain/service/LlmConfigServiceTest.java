package com.sixbean.testpike.core.llmconfig.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigBrief;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInfo;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInput;
import com.sixbean.testpike.core.llmconfig.api.dto.ProjectLlmConfigInfo;
import com.sixbean.testpike.core.llmconfig.domain.service.LlmConfigService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import com.sixbean.testpike.core.support.LlmConfigSharedDbFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class LlmConfigServiceTest {

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
        LlmConfigSharedDbFixture.cleanPreservingFactory(jdbcTemplate, List.of("LLMROUTE"));
    }

    private long createProject() {
        return projectService.create(5L, new com.sixbean.testpike.core.project.api.dto.ProjectCreateInput(
                "LLM路由项目", "LLMROUTE", "zh-CN", "P1", null));
    }

    private LlmConfigInput input(String name, String provider, String modelName, String apiKey,
                                 int status, int isDefault) {
        return new LlmConfigInput(name, provider, modelName, apiKey,
                "https://api." + provider + ".com/v1", status, isDefault, "测试模型");
    }


    @Test
    void createLlmConfig_清单CRUD_apiKey加密打码_DB无明文() {
        LlmConfigInfo created = llmConfigService.create(1L, input(
                "deepseek-main", "deepseek", "deepseek-chat", "sk-test-123456", 1, 1));

        assertNotNull(created.id());
        assertEquals("deepseek-main", created.name());
        assertEquals("deepseek", created.provider());
        assertEquals("deepseek-chat", created.modelName());
        assertEquals("******", created.apiKeyMasked());
        assertEquals(1, created.status());
        assertEquals(1, created.isDefault());

        String stored = jdbcTemplate.queryForObject(
                "SELECT api_key FROM tp_llm_config WHERE id = ?", String.class, created.id());
        assertNotNull(stored);
        assertNotEquals("sk-test-123456", stored, "DB 必须存密文");

        List<LlmConfigInfo> list = llmConfigService.list();
        assertTrue(list.stream().anyMatch(c -> c.id() == created.id() && "******".equals(c.apiKeyMasked())));

        LlmConfigInfo updated = llmConfigService.update(1L, created.id(), input(
                "deepseek-main", "deepseek", "deepseek-chat-0324", null, 0, 0));
        assertEquals("deepseek-chat-0324", updated.modelName());
        assertEquals(0, updated.status());
        assertEquals("******", updated.apiKeyMasked(), "更新后密钥仍不回显");
    }

    @Test
    void createLlmConfig_apiKey为空_可创建_本地网关场景() {
        LlmConfigInfo created = llmConfigService.create(1L, input(
                "local-gw", "openai", "gpt-4o-mini", null, 1, 0));
        assertNotNull(created.id());
        String stored = jdbcTemplate.queryForObject(
                "SELECT api_key FROM tp_llm_config WHERE id = ?", String.class, created.id());
        assertNull(stored, "apiKey 为空则 DB 存 NULL");
    }


    @Test
    void createLlmConfig_重名_409() {
        llmConfigService.create(1L, input("dup-model", "openai", "gpt-4o", "k1", 1, 0));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> llmConfigService.create(1L, input("dup-model", "deepseek", "deepseek-chat", "k2", 1, 0)));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode(), "重名 409");
    }


    @Test
    void listEnabledBriefs_仅启用条目_按默认优先_密钥解密() {
        // Baseline-aware: the generation install-default row seeded by
        // tools/verification/seed_slot_llm_default.py is resident shared baseline (R37);
        // park it around this body instead of deleting it, then restore in finally.
        LlmConfigSharedDbFixture.withParkedGenerationInstallDefaults(jdbcTemplate, () -> {
            llmConfigService.create(1L, input("model-a", "openai", "gpt-4o", "sk-a", 1, 1));
            llmConfigService.create(1L, input("model-b", "deepseek", "deepseek-chat", "sk-b", 1, 0));
            llmConfigService.create(1L, input("model-off", "qwen", "qwen-max", "sk-c", 0, 0));

            java.util.List<LlmConfigBrief> briefs = llmConfigService.listEnabledBriefs();
            assertEquals(2, briefs.size(), "停用条目不进降级链（TC-E7-02 链=启用清单）");
            assertEquals("model-a", briefs.get(0).name(), "系统默认排首位（优先降级目标）");
            assertEquals("model-b", briefs.get(1).name());
            assertEquals("sk-a", briefs.get(0).apiKey(), "解密明文供 ai-service 调用（禁日志同 brief）");
            assertEquals("https://api.openai.com/v1", briefs.get(0).baseUrl());
            });
    }

    @Test
    void listEnabledBriefs_空清单返回空列表() {
        // Baseline-aware: the generation install-default row seeded by
        // tools/verification/seed_slot_llm_default.py is resident shared baseline (R37);
        // park it around this body instead of deleting it, then restore in finally.
        LlmConfigSharedDbFixture.withParkedGenerationInstallDefaults(jdbcTemplate, () -> {
            assertTrue(llmConfigService.listEnabledBriefs().isEmpty(), "空配置 → 空列表（fail-closed 判定输入）");
            });
    }

    @Test
    void updateProjectConfig_停用模型_400人话() {
        LlmConfigInfo disabled = llmConfigService.create(1L, input(
                "disabled-model", "deepseek", "deepseek-chat", "k1", 0, 0));
        long projectId = createProject();

        ServiceException ex = assertThrows(ServiceException.class,
                () -> llmConfigService.updateProjectConfig(5L, projectId, disabled.id()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), "停用模型选用 400（IT-K9-01）");
        assertTrue(ex.getMessage().contains("已停用"), "人话提示：模型已停用");
    }

    @Test
    void updateProjectConfig_模型不存在_404() {
        long projectId = createProject();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> llmConfigService.updateProjectConfig(5L, projectId, 99999L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode(), "模型不存在 404");
    }


    @Test
    void updateProjectConfig_选用后项目详情回显() {
        LlmConfigInfo model = llmConfigService.create(1L, input(
                "deepseek-main", "deepseek", "deepseek-chat", "sk-1", 1, 1));
        long projectId = createProject();

        llmConfigService.updateProjectConfig(5L, projectId, model.id());

        Long storedId = jdbcTemplate.queryForObject(
                "SELECT llm_config_id FROM tp_project WHERE id = ?", Long.class, projectId);
        assertEquals(model.id(), storedId, "tp_project.llm_config_id 落库");

        ProjectLlmConfigInfo info = llmConfigService.getProjectConfig(projectId);
        assertEquals(model.id(), info.llmConfigId());
        assertEquals("deepseek-main", info.llmConfigName());
    }

    @Test
    void updateProjectConfig_传null_解除选用回系统默认() {
        LlmConfigInfo model = llmConfigService.create(1L, input(
                "deepseek-main", "deepseek", "deepseek-chat", "sk-1", 1, 1));
        long projectId = createProject();
        llmConfigService.updateProjectConfig(5L, projectId, model.id());

        llmConfigService.updateProjectConfig(5L, projectId, null);

        ProjectLlmConfigInfo info = llmConfigService.getProjectConfig(projectId);
        assertNull(info.llmConfigId(), "解除选用：回系统默认（NULL）");
    }


    @Test
    void resolveProjectBrief_项目配置优先() {
        LlmConfigInfo defaultModel = llmConfigService.create(1L, input(
                "sys-default", "openai", "gpt-4o", "k-default", 1, 1));
        LlmConfigInfo projectModel = llmConfigService.create(1L, input(
                "deepseek-main", "deepseek", "deepseek-chat", "k-project", 1, 0));
        long projectId = createProject();
        llmConfigService.updateProjectConfig(5L, projectId, projectModel.id());

        LlmConfigBrief brief = llmConfigService.resolveProjectBrief(projectId);
        assertNotNull(brief);
        assertEquals(projectModel.id(), brief.id(), "项目已选用 → 路由到项目模型（TC-K9-02）");
        assertEquals("deepseek", brief.provider());
        assertEquals("deepseek-chat", brief.modelName());
        assertEquals("k-project", brief.apiKey(), "internal 路由返回解密密钥供 ai-service 调用");
        assertEquals("project", brief.source(), "来源=项目选用");
    }

    @Test
    void resolveProjectBrief_未选用_回退系统默认() {
        LlmConfigInfo defaultModel = llmConfigService.create(1L, input(
                "sys-default", "openai", "gpt-4o", "k-default", 1, 1));
        llmConfigService.create(1L, input(
                "non-default", "deepseek", "deepseek-chat", "k2", 1, 0));
        long projectId = createProject();

        LlmConfigBrief brief = llmConfigService.resolveProjectBrief(projectId);
        assertNotNull(brief);
        assertEquals(defaultModel.id(), brief.id(), "项目未选用 → 回退系统默认模型（TC-K9-02）");
        assertEquals("default", brief.source(), "来源=系统默认");
    }

    @Test
    void resolveProjectBrief_无任何配置_null() {
        // Baseline-aware: the generation install-default row seeded by
        // tools/verification/seed_slot_llm_default.py is resident shared baseline (R37);
        // park it around this body instead of deleting it, then restore in finally.
        LlmConfigSharedDbFixture.withParkedGenerationInstallDefaults(jdbcTemplate, () -> {
            long projectId = createProject();
            assertNull(llmConfigService.resolveProjectBrief(projectId), "无清单无选用 → null（ai-service 回退配置文件）");
            });
    }


    @Test
    void createLlmConfig_置默认_旧默认自动降级() {
        LlmConfigInfo first = llmConfigService.create(1L, input(
                "m1", "openai", "gpt-4o", "k1", 1, 1));
        llmConfigService.create(1L, input(
                "m2", "deepseek", "deepseek-chat", "k2", 1, 1));

        Integer firstDefault = jdbcTemplate.queryForObject(
                "SELECT is_default FROM tp_llm_config WHERE id = ?", Integer.class, first.id());
        assertEquals(0, firstDefault, "新默认生效后旧默认自动降级，默认模型唯一");
        Integer secondDefault = jdbcTemplate.queryForObject(
                "SELECT is_default FROM tp_llm_config WHERE id = ?",
                Integer.class, llmConfigService.list().stream()
                        .filter(c -> "m2".equals(c.name())).findFirst().orElseThrow().id());
        assertEquals(1, secondDefault);
    }

    @Test
    void deleteLlmConfig_被项目引用_409人话() {
        LlmConfigInfo model = llmConfigService.create(1L, input(
                "used-model", "deepseek", "deepseek-chat", "sk-1", 1, 1));
        long projectId = createProject();
        llmConfigService.updateProjectConfig(5L, projectId, model.id());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> llmConfigService.delete(model.id()));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode(), "被引用删除 409");
        assertTrue(ex.getMessage().contains("选用"), "人话提示引用方");
    }

    @Test
    void deleteLlmConfig_未被引用_删除生效() {
        LlmConfigInfo model = llmConfigService.create(1L, input(
                "free-model", "deepseek", "deepseek-chat", "sk-1", 1, 0));
        llmConfigService.delete(model.id());
        assertNull(llmConfigService.get(model.id()), "删除后查询为空");
    }


    @Test
    void createLlmConfig_非法status_400() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> llmConfigService.create(1L, input("bad-status", "openai", "gpt-4o", "k1", 2, 0)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), "status 仅 0/1");
    }

    @Test
    void createLlmConfig_缺name或modelName_400() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> llmConfigService.create(1L, new LlmConfigInput(
                        "  ", "openai", "gpt-4o", "k1", "http://x/v1", 1, 0, "无名称")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), "name 必填");
    }


    private LlmConfigInput inputFamily(String name, String family) {
        return new LlmConfigInput(name, "qwen", "qwen3-max", "k1", "http://x/v1", 1, 0,
                "E11 探针", null, family);
    }

    @Test
    void createLlmConfig_tokenizerFamily非法值_400人话() {
        for (String bad : List.of("gpt2", "QWEN", "tokenizer-json", "_SENTENCEPIECE_")) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> llmConfigService.create(1L, inputFamily("e11-bad-" + bad.hashCode(), bad)),
                    "非白名单族必须 400：" + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("auto"), "人话须含合法取值全集：" + ex.getMessage());
        }
    }

    @Test
    void createLlmConfig_tokenizerFamily缺省_auto且落库() {
        LlmConfigInfo created = llmConfigService.create(1L, inputFamily("e11-default", null));
        assertEquals("auto", created.tokenizerFamily());
        LlmConfigInfo blank = llmConfigService.create(1L, inputFamily("e11-blank", "   "));
        assertEquals("auto", blank.tokenizerFamily());
        Integer stored = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_llm_config WHERE tokenizer_family='auto' AND name LIKE 'e11-%'",
                Integer.class);
        assertEquals(2, stored, "V35 DEFAULT 'auto' + 归一化双保险");
    }

    @Test
    void createLlmConfig_tokenizerFamily白名单全集_落库回显() {
        for (String ok : List.of("auto", "qwen", "deepseek", "cl100k", "sp-char")) {
            LlmConfigInfo created = llmConfigService.create(1L, inputFamily("e11-ok-" + ok, ok));
            assertEquals(ok, created.tokenizerFamily(), "白名单族存取一致：" + ok);
        }
        LlmConfigInfo upd = llmConfigService.update(1L,
                llmConfigService.list().stream().filter(c -> "e11-ok-qwen".equals(c.name()))
                        .findFirst().orElseThrow().id(), inputFamily("e11-ok-qwen", "cl100k"));
        assertEquals("cl100k", upd.tokenizerFamily());
    }


    private LlmConfigInput embInput(String name, Integer dimension, int isDefault) {
        return new LlmConfigInput(name, "dashscope-compatible", "text-embedding-v3", "sk-emb-1",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", 1, isDefault,
                "embedding 探针", null, null, 2, dimension);
    }

    private int isDefaultOf(long id) {
        Integer v = jdbcTemplate.queryForObject(
                "SELECT is_default FROM tp_llm_config WHERE id = ?", Integer.class, id);
        return v == null ? -1 : v;
    }

    @Test
    void createLlmConfig_embedding缺维度_400人话点名必填() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> llmConfigService.create(1L, embInput("emb-no-dim", null, 0)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("向量维度"), "人话须点名『向量维度』必填：" + ex.getMessage());

        for (Integer bad : List.of(0, -1, 99999)) {
            ServiceException e2 = assertThrows(ServiceException.class,
                    () -> llmConfigService.create(1L, embInput("emb-bad-" + bad, bad, 0)),
                    "非法维度必须 400：" + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, e2.getCode());
            assertTrue(e2.getMessage().contains("1~16000"), "人话须含合法区间：" + e2.getMessage());
        }

        ServiceException e3 = assertThrows(ServiceException.class,
                () -> llmConfigService.create(1L, new LlmConfigInput("emb-bad-type", "openai",
                        "m", "k", "http://x/v1", 1, 0, "类型非法", null, null, 5, 1024)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e3.getCode());
        assertTrue(e3.getMessage().contains("1=生成"), "人话须含合法取值：" + e3.getMessage());

        ServiceException e4 = assertThrows(ServiceException.class,
                () -> llmConfigService.create(1L, new LlmConfigInput("emb-no-url", "dashscope-compatible",
                        "text-embedding-v3", "k", "  ", 1, 0, "缺端点", null, null, 2, 1024)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e4.getCode());
        assertTrue(e4.getMessage().contains("端点"), "人话须点名端点：" + e4.getMessage());
    }

    @Test
    void createLlmConfig_embedding设默认_生成默认不被挤占_同类顶替分域() {
        LlmConfigInfo gen = llmConfigService.create(1L, input("gen-default", "openai", "gpt-4o", "k1", 1, 1));
        LlmConfigInfo emb = llmConfigService.create(1L, embInput("emb-default", 1024, 1));

        assertEquals(2, emb.modelType(), "类型落库回显");
        assertEquals(1024, emb.embeddingDimension(), "维度落库回显");
        assertEquals(1, isDefaultOf(gen.id()), "⚠️ 生成默认不被 embedding 设默认挤占（分域）");
        assertEquals(1, isDefaultOf(emb.id()));

        LlmConfigInfo emb2 = llmConfigService.create(1L, embInput("emb-default-2", 1024, 1));
        assertEquals(0, isDefaultOf(emb.id()), "embedding 同类新默认顶替旧默认");
        assertEquals(1, isDefaultOf(emb2.id()));
        assertEquals(1, isDefaultOf(gen.id()), "顶替全程生成默认无损");

        llmConfigService.create(1L, input("gen-default-2", "deepseek", "deepseek-chat", "k2", 1, 1));
        assertEquals(1, isDefaultOf(emb2.id()), "生成新默认不挤占 embedding 默认");
    }

    @Test
    void embedding默认_部分唯一索引DB兜底_分域唯一常驻() {
        // IT-EMB-02 backstop tier: bypassing the service layer and inserting
        // directly must still be rejected by the DB in the concurrent window
        // (never rely on the service-side clearing alone).
        // After R37 the factory embedding default stays resident, so a direct
        // second type-2 default insert must be rejected by the same partial
        // unique index (the factory row naturally acts as the first default —
        // the rejection tier is now covered even more strongly).
        llmConfigService.create(1L, input("gen-def", "openai", "gpt-4o", "k1", 1, 1));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("""
                        INSERT INTO tp_llm_config (name, provider, model_name, status, is_default, model_type)
                        VALUES ('race-gen-def', 'openai', 'gpt-4o-mini', 1, 1, 1)
                        """), "同类第二默认直插必须被 uk_llm_default_gen 拒绝");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("""
                        INSERT INTO tp_llm_config (name, provider, model_name, status, is_default, model_type, embedding_dimension)
                        VALUES ('race-emb-def', 'dashscope-compatible', 'text-embedding-v3', 1, 1, 2, 1024)
                        """), "出厂向量默认在册时 embedding 域第二默认直插必须被同类索引拒绝");
        Integer defaults = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_llm_config WHERE is_default = 1", Integer.class);
        assertEquals(2, defaults, "生成默认+出厂 embedding 默认跨域并存（分域索引允许）");
    }

    @Test
    void embedding配置_不进生成降级链与路由() {
        // Baseline-aware: the generation install-default row seeded by
        // tools/verification/seed_slot_llm_default.py is resident shared baseline (R37);
        // park it around this body instead of deleting it, then restore in finally.
        LlmConfigSharedDbFixture.withParkedGenerationInstallDefaults(jdbcTemplate, () -> {
            llmConfigService.create(1L, embInput("emb-only", 1024, 1));
            assertTrue(llmConfigService.listEnabledBriefs().isEmpty(), "降级链=生成清单，embedding 行零进入");
            long projectId = createProject();
            assertNull(llmConfigService.resolveProjectBrief(projectId), "项目回退不得命中 embedding 默认");
            LlmConfigInfo emb = llmConfigService.list().stream()
                    .filter(c -> "emb-only".equals(c.name())).findFirst().orElseThrow();
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> llmConfigService.updateProjectConfig(5L, projectId, emb.id()));
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("embedding"), "人话点名类型错配：" + ex.getMessage());
            });
    }

    @Test
    void embedding行_apiKey恒打码_存量D5基径不破() {
        LlmConfigInfo emb = llmConfigService.create(1L, embInput("emb-mask", 1024, 0));
        assertEquals("******", emb.apiKeyMasked());
        String stored = jdbcTemplate.queryForObject(
                "SELECT api_key FROM tp_llm_config WHERE id = ?", String.class, emb.id());
        assertNotNull(stored);
        assertNotEquals("sk-emb-1", stored, "DB 存密文非明文");
    }

    @Test
    void list_modelType筛选_全集两条链路() {
        // Baseline-aware: list() has no status filter and sorts the
        // default row first, so parking would not hide the resident install
        // row; assert the created rows as deltas of the same baseline snapshot
        // instead (R37: resident rows are never deleted by tests).
        int totalBase = llmConfigService.list(null).size();
        int genBase = llmConfigService.list(1).size();
        int embBase = llmConfigService.list(2).size();
        llmConfigService.create(1L, input("gen-1", "openai", "gpt-4o", "k1", 1, 0));
        llmConfigService.create(1L, embInput("emb-1", 1024, 0));
        assertEquals(totalBase + 2, llmConfigService.list(null).size(), "null=全集（R37 出厂行常驻并存）");
        assertEquals(genBase + 1, llmConfigService.list(1).size(), "type=1 筛选=常驻基线+本批生成行");
        assertTrue(llmConfigService.list(1).stream().anyMatch(c -> "gen-1".equals(c.name())),
                "type=1 筛选含本批行");
        assertEquals(embBase + 1, llmConfigService.list(2).size(), "type=2 筛选=常驻基线+本批向量行");
        assertTrue(llmConfigService.list(2).stream().anyMatch(c -> "emb-1".equals(c.name())),
                "type=2 筛选含本批行");
        assertTrue(llmConfigService.list(9).isEmpty(), "枚举外=空集不报错（筛选参数宽容）");
    }

    @Test
    void resolveProjectBrief_tokenizerFamily透传() {
        LlmConfigInfo created = llmConfigService.create(1L, inputFamily("e11-route", "qwen"));
        long projectId = createProject();
        llmConfigService.updateProjectConfig(5L, projectId, created.id());
        LlmConfigBrief brief = llmConfigService.resolveProjectBrief(projectId);
        assertNotNull(brief);
        assertEquals("qwen", brief.tokenizerFamily(), "internal 路由响应须透传族（Registry 消费）");
        assertTrue(llmConfigService.listEnabledBriefs().stream()
                        .anyMatch(b -> "qwen".equals(b.tokenizerFamily())),
                "降级链条目须带族（chatHandle 降级候选端点按新端点族复估）");
    }
}
