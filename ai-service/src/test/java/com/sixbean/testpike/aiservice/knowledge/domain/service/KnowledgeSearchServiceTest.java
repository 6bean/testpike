package com.sixbean.testpike.aiservice.knowledge.domain.service;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchItem;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchRequest;
import com.sixbean.testpike.aiservice.knowledge.domain.component.FakeEmbeddingModel;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeHitRecorder;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeLangResolver;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeMetadataFields;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeSearchConfigResolver;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeSearchProperties;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeSearchScene;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeVectorStoreTestConfig;
import com.sixbean.testpike.aiservice.knowledge.domain.service.impl.KnowledgeSearchServiceImpl;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


@SpringBootTest
@Import(KnowledgeVectorStoreTestConfig.class)
class KnowledgeSearchServiceTest {

    private static final long PROJECT_A = 7001L;
    private static final long PROJECT_B = 7002L;

    @Autowired
    private PgVectorStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<String> createdIds = new ArrayList<>();

    @AfterEach
    void cleanupCreatedChunks() {
        if (!createdIds.isEmpty()) {
            store.delete(createdIds);
            createdIds.clear();
        }
    }

    
    private static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(i == 0 ? "" : ",").append(v[i]);
        }
        return sb.append(']').toString();
    }

    
    private void seed(long projectId, String sourceType, boolean excellent, String lang,
                      String title, boolean orthogonal) {
        seed(projectId, sourceType, excellent, lang, title, orthogonal ? 0.0 : 1.0);
    }

    
    private void seed(long projectId, String sourceType, boolean excellent, String lang,
                      String title, double similarity) {
        String id = UUID.randomUUID().toString();
        createdIds.add(id);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(KnowledgeMetadataFields.DOC_ID, 1L);
        metadata.put(KnowledgeMetadataFields.PROJECT_ID, projectId);
        metadata.put(KnowledgeMetadataFields.PACK_ID, 0L);
        metadata.put(KnowledgeMetadataFields.SOURCE_TYPE, sourceType);
        metadata.put(KnowledgeMetadataFields.CASE_ID, 0L);
        metadata.put(KnowledgeMetadataFields.CHUNK_INDEX, 0);
        metadata.put(KnowledgeMetadataFields.TITLE, title);
        metadata.put(KnowledgeMetadataFields.LANG, lang);
        metadata.put(KnowledgeMetadataFields.EXCELLENT, excellent);
        metadata.put(KnowledgeMetadataFields.STATUS, KnowledgeMetadataFields.ACTIVE);
        store.add(List.of(Document.builder().id(id).text("标题 " + title).metadata(metadata).build()));
        if (similarity < 1.0) {
            float[] v = new float[FakeEmbeddingModel.DIMENSION];
            v[0] = (float) similarity;
            v[1] = (float) Math.sqrt(1.0 - similarity * similarity);
            jdbcTemplate.update("update knowledge.tp_knowledge_chunk set embedding = ?::vector where id = ?",
                    toVectorLiteral(v), UUID.fromString(id));
        }
    }

    private KnowledgeSearchServiceImpl service(KnowledgeSearchProperties props) {
        CoreGateway gateway = mock(CoreGateway.class);
        when(gateway.getSysConfig(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        when(gateway.getProject(anyLong())).thenReturn(new CoreGateway.ProjectBrief(1L, "P", "zh-CN", 1));
        KnowledgeLangResolver langResolver = new KnowledgeLangResolver(gateway);
        return new KnowledgeSearchServiceImpl(store, new KnowledgeSearchConfigResolver(gateway, props),
                props, langResolver, mock(KnowledgeHitRecorder.class),
                new NamedParameterJdbcTemplate(jdbcTemplate));
    }

    private KnowledgeSearchRequest request(long projectId, String scene, Integer topK) {
        return new KnowledgeSearchRequest(projectId, "接口命名规范", scene, topK, null);
    }

    private static List<String> titles(List<KnowledgeSearchItem> items) {
        return items.stream().map(KnowledgeSearchItem::title).toList();
    }

    // ---------------- routing criterion: CASE_GEN respects use_in_case_prompt ----------------

    /** Ledger fixture: a tp_knowledge_doc row backing an uploaded document
     *  (source_ref lives in the tp_document id domain, same shape as the
     *  ingest service writes on production). */
    private void seedLedgerRow(long ledgerId, long documentId, String title) {
        jdbcTemplate.update("INSERT INTO tp_knowledge_doc (id, project_id, pack_id, source_type, "
                        + "source_ref, title, content) OVERRIDING SYSTEM VALUE "
                        + "VALUES (?, ?, 0, 'TEST_SPEC', ?, ?, ?)",
                ledgerId, PROJECT_A, documentId, title, "台账正文 " + title);
    }

    /** Chunk whose doc_id metadata carries the KNOWLEDGE LEDGER id — the id
     *  domain retrieval results actually use (KnowledgeIngestServiceImpl
     *  writes chunks keyed on tp_knowledge_doc.id, never tp_document.id). */
    private void seedLedgerChunk(long ledgerId, String title) {
        String id = UUID.randomUUID().toString();
        createdIds.add(id);
        Map<String, Object> meta = new HashMap<>();
        meta.put(KnowledgeMetadataFields.DOC_ID, ledgerId);
        meta.put(KnowledgeMetadataFields.PROJECT_ID, PROJECT_A);
        meta.put(KnowledgeMetadataFields.PACK_ID, 0L);
        meta.put(KnowledgeMetadataFields.SOURCE_TYPE, "TEST_SPEC");
        meta.put(KnowledgeMetadataFields.CASE_ID, 0L);
        meta.put(KnowledgeMetadataFields.CHUNK_INDEX, 0);
        meta.put(KnowledgeMetadataFields.TITLE, title);
        meta.put(KnowledgeMetadataFields.LANG, "zh");
        meta.put(KnowledgeMetadataFields.EXCELLENT, false);
        meta.put(KnowledgeMetadataFields.STATUS, KnowledgeMetadataFields.ACTIVE);
        store.add(List.of(Document.builder().id(id).text("标题 " + title).metadata(meta).build()));
    }

    private void insertUploadDoc(long documentId, String hash, String name, boolean useInCasePrompt) {
        jdbcTemplate.update("INSERT INTO tp_document (id, project_id, file_hash, name, status, "
                        + "use_in_case_prompt) OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?, 1, ?)",
                documentId, PROJECT_A, hash, name, useInCasePrompt);
    }

    @Test
    void casePromptSwitchOffExcludesDocFromCaseGenHitsOnly() {
        // Distinct ids per domain on purpose: the switch reader resolves ledger
        // ids through source_ref, so a chunk doc_id equal to a tp_document id
        // must NOT be treated as the same key (id-domain confusion regression).
        long gatedDocId = 990041L;
        long controlDocId = 990042L;
        long gatedLedgerId = 990141L;
        long controlLedgerId = 990142L;
        String hash = "ledger-fixture-" + UUID.randomUUID();
        insertUploadDoc(gatedDocId, hash, "关闭生成位的参考文档", false);
        insertUploadDoc(controlDocId, hash + "-ctl", "照常参与生成的参考文档", true);
        seedLedgerRow(gatedLedgerId, gatedDocId, "被关生成位参考语料");
        seedLedgerRow(controlLedgerId, controlDocId, "照常生成参考语料");
        try {
            seedLedgerChunk(gatedLedgerId, "被关生成位参考语料");
            seedLedgerChunk(controlLedgerId, "照常生成参考语料");

            KnowledgeSearchProperties props = new KnowledgeSearchProperties();
            List<KnowledgeSearchItem> caseGen = service(props)
                    .search(request(PROJECT_A, "CASE_GEN", null));
            assertFalse(titles(caseGen).contains("被关生成位参考语料"),
                    "CASE_GEN 命中集禁含关闭「参与用例生成提示词」的文档: " + titles(caseGen));
            assertTrue(titles(caseGen).contains("照常生成参考语料"), "开位对照文档照常命中: " + titles(caseGen));

            List<KnowledgeSearchItem> business = service(props)
                    .search(request(PROJECT_A, "BUSINESS", null));
            assertTrue(titles(business).contains("被关生成位参考语料"),
                    "生成提示词开关只闸 CASE_GEN，接地路（BUSINESS）由接地组装处自判: " + titles(business));
        } finally {
            jdbcTemplate.update("DELETE FROM tp_knowledge_doc WHERE id IN (?, ?)",
                    gatedLedgerId, controlLedgerId);
            jdbcTemplate.update("DELETE FROM tp_document WHERE id IN (?, ?)", gatedDocId, controlDocId);
        }
    }


    @Test
    void TC_G2_01_双路融合含项目A与全局_零泄漏项目B() {
        seed(PROJECT_A, "TEST_CASE", false, "zh", "A项目用例规范", false);
        seed(0L, "TEST_SPEC", false, "zh", "全局通用规范", false);
        seed(PROJECT_B, "TEST_CASE", false, "zh", "B项目私有", false);

        List<KnowledgeSearchItem> items = service(new KnowledgeSearchProperties())
                .search(request(PROJECT_A, "CASE_GEN", null));

        assertTrue(items.stream().anyMatch(i -> "A项目用例规范".equals(i.title())), "项目内链路必命中：" + titles(items));
        assertTrue(items.stream().anyMatch(i -> "全局通用规范".equals(i.title())), "全局链路必命中（H5）：" + titles(items));
        assertFalse(items.stream().anyMatch(i -> "B项目私有".equals(i.title())),
                "跨项目零泄漏（14 §9.2 应用层强制）：" + titles(items));
    }

    @Test
    void IT_KB_01_INACTIVE行检索必过滤() {
        String id = UUID.randomUUID().toString();
        createdIds.add(id);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(KnowledgeMetadataFields.PROJECT_ID, PROJECT_A);
        metadata.put(KnowledgeMetadataFields.STATUS, KnowledgeMetadataFields.INACTIVE);
        metadata.put(KnowledgeMetadataFields.SOURCE_TYPE, "TEST_SPEC");
        metadata.put(KnowledgeMetadataFields.EXCELLENT, false);
        metadata.put(KnowledgeMetadataFields.LANG, "zh");
        metadata.put(KnowledgeMetadataFields.TITLE, "已下线规范");
        store.add(List.of(Document.builder().id(id).text("旧版").metadata(metadata).build()));

        List<KnowledgeSearchItem> items = service(new KnowledgeSearchProperties())
                .search(request(PROJECT_A, "CASE_GEN", null));
        assertFalse(titles(items).contains("已下线规范"), "T177 软下线行禁进结果集（14 §4.4）");
    }


    @Test
    void IT_KB_02_阈值边界_低于不注入_达标命中() {
        seed(PROJECT_A, "TEST_SPEC", false, "zh", "达标命中", 0.9);
        seed(PROJECT_A, "TEST_SPEC", false, "zh", "低于阈值排除", 0.5);

        KnowledgeSearchProperties props = new KnowledgeSearchProperties();
        props.setCaseGenThreshold(0.8);
        List<KnowledgeSearchItem> items = service(props).search(request(PROJECT_A, "CASE_GEN", null));

        assertTrue(titles(items).contains("达标命中"), "达标命中：" + titles(items));
        assertFalse(titles(items).contains("低于阈值排除"), "低于阈值不注入防噪音：" + titles(items));
    }


    @Test
    void rerank_excellent加权仅TEST_CASE_SPEC反馈各按其系数() {
        seed(PROJECT_A, "TEST_CASE", true, "zh-CN", "优秀用例", 0.9);        // 0.9*1.2+0.05=1.13
        seed(PROJECT_A, "TEST_SPEC", true, "en", "规范也带excellent", 0.9);
        seed(PROJECT_A, "FEEDBACK", false, "en", "反馈样本", 0.9);            // 0.9*0.8=0.72

        KnowledgeSearchProperties props = new KnowledgeSearchProperties();
        props.setFusionMode("weighted");
        props.setGlobalWeight(0.0);
        List<KnowledgeSearchItem> items = service(props).search(request(PROJECT_A, "CASE_GEN", null));

        assertEquals(List.of("优秀用例", "规范也带excellent", "反馈样本"), titles(items),
                "排序=excellent×1.2 仅 TEST_CASE、SPEC×1.1、FEEDBACK×0.8（14 §5.2）");
        Map<String, Double> byTitle = new HashMap<>();
        items.forEach(i -> byTitle.put(i.title(), i.score()));
        assertEquals(0.9 * 1.2 + 0.05, byTitle.get("优秀用例"), 1e-6, "lang 命中 +0.05 叠加");
        assertEquals(0.9 * 1.1, byTitle.get("规范也带excellent"), 1e-6,
                "TEST_SPEC 类 excellent=true 不乘用例系数（×1.2 仅 TEST_CASE）");
        assertEquals(0.9 * 0.8, byTitle.get("反馈样本"), 1e-6);
    }

    @Test
    void lang一致加分0_05_不一致不加分() {
        seed(PROJECT_A, "TEST_SPEC", false, "zh-CN", "同语言", false);
        seed(PROJECT_A, "TEST_SPEC", false, "en", "异语言", false);

        KnowledgeSearchProperties props = new KnowledgeSearchProperties();
        props.setFusionMode("weighted");
        List<KnowledgeSearchItem> items = service(props).search(request(PROJECT_A, "CASE_GEN", null));

        assertEquals(List.of("同语言", "异语言"), titles(items), "lang==项目默认语言(+0.05) 排前");
        double zh = items.get(0).score();
        double en = items.get(1).score();
        assertEquals(0.05, zh - en, 1e-6, "加分差=lang-bonus 面值");
    }


    @Test
    void weighted融合_项目内0_7_全局0_3() {
        seed(PROJECT_A, "TEST_CASE", false, "en", "项目内", false);
        seed(0L, "TEST_CASE", false, "en", "全局", false);

        KnowledgeSearchProperties props = new KnowledgeSearchProperties();
        props.setFusionMode("weighted");
        List<KnowledgeSearchItem> items = service(props).search(request(PROJECT_A, "CASE_GEN", null));

        assertEquals(List.of("项目内", "全局"), titles(items), "项目内权重链路压过全局（H5 项目内优先）");
        assertEquals(0.7, items.get(0).score(), 1e-6);
        assertEquals(0.3, items.get(1).score(), 1e-6, "global-weight=0.3（14 §5.1）");
    }

    @Test
    void RRF融合_k60_两条链路名次合成() {
        seed(PROJECT_A, "TEST_CASE", false, "en", "A链路第1", 1.0);
        seed(PROJECT_A, "TEST_CASE", false, "en", "A链路第2", 0.9);
        seed(0L, "TEST_CASE", false, "en", "B链路第1", 1.0);

        KnowledgeSearchProperties props = new KnowledgeSearchProperties();
        props.setFusionMode("rrf");
        List<KnowledgeSearchItem> items = service(props).search(request(PROJECT_A, "CASE_GEN", null));

        assertEquals(3, items.size(), "三行全融合：" + titles(items));
        assertEquals(1.0 / 61, items.get(0).score(), 1e-6, "rank1 RRF 分=1/(60+1)");
        KnowledgeSearchItem last = items.get(2);
        assertEquals(1.0 / 62, last.score(), 1e-6, "A 链路第 2 名=1/(60+2) 排末");
        assertEquals("A链路第2", last.title());
        assertTrue(titles(items).subList(0, 2).containsAll(List.of("A链路第1", "B链路第1")),
                "两条链路 rank1 并列压过 rank2：" + titles(items));
    }


    @Test
    void 场景topK截断_请求覆写生效_未知场景人话拒() {
        for (int i = 1; i <= 7; i++) {
            seed(PROJECT_A, "TEST_SPEC", false, "en", "行" + i, false);
        }
        List<KnowledgeSearchItem> items = service(new KnowledgeSearchProperties())
                .search(request(PROJECT_A, "CASE_GEN", null));
        assertEquals(5, items.size(), "场景默认 topK 截断");

        List<KnowledgeSearchItem> overridden = service(new KnowledgeSearchProperties())
                .search(request(PROJECT_A, "CASE_GEN", 2));
        assertEquals(2, overridden.size(), "请求 topK 覆写（重排后截断）");

        assertThrows(ServiceException.class,
                () -> service(new KnowledgeSearchProperties()).search(request(PROJECT_A, "NO_SUCH", null)),
                "未知场景人话拒（禁静默按兜底跑）");
    }

    @Test
    void 场景枚举面值锚_14_5_3表() {
        assertEquals(5, KnowledgeSearchScene.CASE_GEN.getDefaultTopK());
        assertEquals(0.65, KnowledgeSearchScene.CASE_GEN.getDefaultThreshold(), 1e-9);
        assertEquals(3, KnowledgeSearchScene.SIMILARITY.getDefaultTopK());
        assertEquals(0.80, KnowledgeSearchScene.SIMILARITY.getDefaultThreshold(), 1e-9);
        assertEquals(3, KnowledgeSearchScene.LOCATOR.getDefaultTopK());
        assertEquals(0.75, KnowledgeSearchScene.LOCATOR.getDefaultThreshold(), 1e-9);
        assertEquals(5, KnowledgeSearchScene.BUSINESS.getDefaultTopK());
        assertEquals(0.70, KnowledgeSearchScene.BUSINESS.getDefaultThreshold(), 1e-9);
        assertEquals(3, KnowledgeSearchScene.FALLBACK.getDefaultTopK());
        assertEquals(0.70, KnowledgeSearchScene.FALLBACK.getDefaultThreshold(), 1e-9);
    }
}
