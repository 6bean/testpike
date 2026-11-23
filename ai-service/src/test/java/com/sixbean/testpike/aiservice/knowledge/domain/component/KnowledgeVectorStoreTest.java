package com.sixbean.testpike.aiservice.knowledge.domain.component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest
@Import(KnowledgeVectorStoreTestConfig.class)
class KnowledgeVectorStoreTest {

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

    private Document chunk(long projectId, String status, String title, String content) {
        String id = UUID.randomUUID().toString();
        createdIds.add(id);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(KnowledgeMetadataFields.DOC_ID, 1L);
        metadata.put(KnowledgeMetadataFields.PROJECT_ID, projectId);
        metadata.put(KnowledgeMetadataFields.PACK_ID, 0L);
        metadata.put(KnowledgeMetadataFields.SOURCE_TYPE, "TEST_SPEC");
        metadata.put(KnowledgeMetadataFields.CASE_ID, 0L);
        metadata.put(KnowledgeMetadataFields.CHUNK_INDEX, 0);
        metadata.put(KnowledgeMetadataFields.TITLE, title);
        metadata.put(KnowledgeMetadataFields.LANG, "zh");
        metadata.put(KnowledgeMetadataFields.EXCELLENT, false);
        metadata.put(KnowledgeMetadataFields.STATUS, status);
        return Document.builder().id(id).text(content).metadata(metadata).build();
    }

    @Test
    void V64结构实查_jsonb_HNSW_WITH_GIN_台账唯一_维度1536() {
        String embeddingType = jdbcTemplate.queryForObject(
                "select format_type(atttypid, atttypmod) from pg_attribute"
                        + " where attrelid = 'knowledge.tp_knowledge_chunk'::regclass"
                        + " and attname = 'embedding'", String.class);
        assertEquals("vector(1536)", embeddingType, "列维度固化（14 §4.2，IT-VEC-01 锚）");

        String metadataType = jdbcTemplate.queryForObject(
                "select atttypid::regtype::text from pg_attribute"
                        + " where attrelid = 'knowledge.tp_knowledge_chunk'::regclass"
                        + " and attname = 'metadata'", String.class);
        assertEquals("jsonb", metadataType, "元数据 jsonb（Spring AI 自动 DDL 是 json=表创建权收归 V64 的根因）");

        String hnswDef = jdbcTemplate.queryForObject(
                "select indexdef from pg_indexes where schemaname='knowledge'"
                        + " and tablename='tp_knowledge_chunk' and indexname='idx_knowledge_chunk_hnsw'",
                String.class);
        assertTrue(hnswDef.contains("hnsw") && hnswDef.contains("m='16'")
                        && hnswDef.contains("ef_construction='64'"),
                "HNSW 构建参数与 14 §4.4 一致：" + hnswDef);

        String ginDef = jdbcTemplate.queryForObject(
                "select indexdef from pg_indexes where schemaname='knowledge'"
                        + " and tablename='tp_knowledge_chunk' and indexname='idx_knowledge_chunk_meta'",
                String.class);
        assertTrue(ginDef.contains("gin") && ginDef.contains("jsonb_path_ops"),
                "元数据 GIN(jsonb_path_ops) 在位：" + ginDef);

        Integer legacyUnique = jdbcTemplate.queryForObject(
                "select count(*) from pg_constraint where conrelid='public.tp_knowledge_doc'::regclass"
                        + " and conname='uq_knowledge_doc_pack_title'", Integer.class);
        assertEquals(0, legacyUnique, "V65：旧 UNIQUE(pack_id,title) 全列唯一必须已删除");
        Integer entityIndex = jdbcTemplate.queryForObject(
                "select count(*) from pg_indexes where schemaname='public'"
                        + " and tablename='tp_knowledge_doc' and indexname='uq_kb_doc_entity'", Integer.class);
        assertEquals(1, entityIndex, "部分唯一 uq_kb_doc_entity(source_type,project_id,source_ref) WHERE ref 非空（V65）");
        Integer titleIndex = jdbcTemplate.queryForObject(
                "select count(*) from pg_indexes where schemaname='public'"
                        + " and tablename='tp_knowledge_doc' and indexname='uq_kb_doc_title'", Integer.class);
        assertEquals(1, titleIndex, "部分唯一 uq_kb_doc_title(pack_id,project_id,title) WHERE ref 空（V65）");
    }

    @Test
    void add双chunk_filterExpression按项目与状态过滤命中() {
        store.add(List.of(
                chunk(0L, KnowledgeMetadataFields.ACTIVE, "通用规范A", "接口命名须用动宾结构"),
                chunk(7L, KnowledgeMetadataFields.ACTIVE, "项目B私有", "本项目金额保留两位小数"),
                chunk(0L, KnowledgeMetadataFields.INACTIVE, "已下线文档", "旧版命名规范")));

        List<Document> hits = store.similaritySearch(SearchRequest.builder()
                .query("接口命名")
                .topK(10)
                .similarityThreshold(0.9)
                .filterExpression("project_id == 0 && status == 'ACTIVE'")
                .build());

        assertTrue(hits.stream().anyMatch(d -> "通用规范A".equals(
                d.getMetadata().get(KnowledgeMetadataFields.TITLE))), "目标 chunk 必命中");
        assertTrue(hits.stream().allMatch(d -> Long.valueOf(0L).equals(toLong(
                        d.getMetadata().get(KnowledgeMetadataFields.PROJECT_ID)))
                        && KnowledgeMetadataFields.ACTIVE.equals(
                        d.getMetadata().get(KnowledgeMetadataFields.STATUS))),
                "过滤链路零泄漏：他项目/已下线禁进结果集（14 §4.4 GIN 过滤语义）："
                        + hits.stream().map(d -> d.getMetadata().get(KnowledgeMetadataFields.TITLE)).toList());
    }

    private static Long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    @Test
    void IT_VEC_01_维度不符装配fail_fast人话点名重建() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> KnowledgeVectorStoreConfig.assertEmbeddingColumnDimension(
                        jdbcTemplate, "knowledge", "tp_knowledge_chunk", 999));
        assertTrue(e.getMessage().contains("knowledge.tp_knowledge_chunk")
                        && e.getMessage().contains("999") && e.getMessage().contains("1536")
                        && e.getMessage().contains("重建"),
                "fail-fast 人话=点名表/期望/实际/重建指引（禁静默写错维度向量）：" + e.getMessage());

        KnowledgeVectorStoreConfig.assertEmbeddingColumnDimension(
                jdbcTemplate, "knowledge", "tp_knowledge_chunk", FakeEmbeddingModel.DIMENSION);
    }

    @Test
    void 同id重复add_upsert零重复行() {
        Document first = chunk(0L, KnowledgeMetadataFields.ACTIVE, "dup-初版", "初版内容");
        store.add(List.of(first));
        store.add(List.of(Document.builder().id(first.getId()).text("改后内容")
                .metadata(first.getMetadata()).build()));

        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from knowledge.tp_knowledge_chunk where id = ?",
                Integer.class, UUID.fromString(first.getId()));
        assertEquals(1, rows, "同 id 重灌=UPSERT 覆盖（T176 重建「先删后插保最新」的存储前提）");
        String content = jdbcTemplate.queryForObject(
                "select content from knowledge.tp_knowledge_chunk where id = ?",
                String.class, UUID.fromString(first.getId()));
        assertEquals("改后内容", content, "覆盖语义=后写生效");
    }

    @Test
    void delete后检索与表行均不含() {
        Document doomed = chunk(0L, KnowledgeMetadataFields.ACTIVE, "待删文档", "即将物理删除");
        store.add(List.of(doomed));

        store.delete(List.of(doomed.getId()));

        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from knowledge.tp_knowledge_chunk where id = ?",
                Integer.class, UUID.fromString(doomed.getId()));
        assertEquals(0, rows, "向量行物理删（PRD 6.6 可重建语义）");
        List<Document> hits = store.similaritySearch(SearchRequest.builder()
                .query("即将物理删除").topK(5).similarityThreshold(0.9)
                .filterExpression("title == '待删文档'").build());
        assertTrue(hits.isEmpty(), "删除后检索必不可达");
    }

    @Test
    void store装配为真PgVectorStore且表名校验在位() {
        assertTrue(store instanceof VectorStore, "bean 装配成功（initializeSchema=false 下 V64 表校验通过）");
    }
}
