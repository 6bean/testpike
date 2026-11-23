package com.sixbean.testpike.aiservice.knowledge.domain.service;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeReconcileResult;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeVectorStoreTestConfig;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmAuditRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;


@SpringBootTest
@Import(KnowledgeVectorStoreTestConfig.class)
class KnowledgeReconcileServiceTest {

    
    private static final long PROJECT_ID = 987_654_320L;

    @Autowired
    private KnowledgeReconcileService reconcileService;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CoreGateway coreGateway;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM knowledge.tp_knowledge_chunk WHERE metadata->>'project_id' = ?",
                String.valueOf(PROJECT_ID));
        jdbcTemplate.update("DELETE FROM knowledge.tp_knowledge_chunk WHERE metadata->>'doc_id' = '888888'");
        jdbcTemplate.update("DELETE FROM public.tp_knowledge_doc WHERE project_id = ?", PROJECT_ID);
    }

    private long insertLedger(String sourceType, long sourceRef, String title,
                              String content, String status) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_knowledge_doc (project_id, pack_id, source_type, source_ref,"
                        + " title, content, status) VALUES (?, 0, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, PROJECT_ID, sourceType, sourceRef, title, content, status);
        return id == null ? -1 : id;
    }

    private void insertVectorRow(long docId, long projectId, String sourceType, long caseId,
                                 String content, String status) {
        vectorStore.add(List.of(Document.builder()
                .id(UUID.nameUUIDFromBytes(("recon-test:" + docId).getBytes(StandardCharsets.UTF_8)).toString())
                .text(content)
                .metadata(Map.of("doc_id", docId, "project_id", projectId, "pack_id", 0L,
                        "source_type", sourceType, "case_id", caseId, "chunk_index", 1,
                        "title", "重建标题", "lang", "zh-CN", "excellent", false, "status", status))
                .build()));
    }

    private int vectorRowsOf(long docId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM knowledge.tp_knowledge_chunk WHERE metadata->>'doc_id' = ?",
                Integer.class, String.valueOf(docId));
        return n == null ? 0 : n;
    }

    @Test
    void 对账_missing台账ACTIVE无向量行_按台账content重嵌入补并留痕() {
        long docId = insertLedger("TEST_CASE", 555L, "登录成功用例", "预期结果：登录成功", "ACTIVE");

        KnowledgeReconcileResult result = reconcileService.reconcile();

        assertTrue(result.repairedDocIds().contains(docId), "missing 检出清单（IT-VEC-05）");
        assertEquals(1, vectorRowsOf(docId), "按台账 content 重嵌入补（纯台账驱动，14 §6.3/6.6）");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT content, metadata->>'status' as status, metadata->>'case_id' as case_id,"
                        + " metadata->>'chunk_index' as chunk_index"
                        + " FROM knowledge.tp_knowledge_chunk WHERE metadata->>'doc_id' = ?",
                String.valueOf(docId));
        assertEquals("预期结果：登录成功", row.get("content"));
        assertEquals("ACTIVE", row.get("status"));
        assertEquals("555", row.get("case_id"), "用例链路 case_id=source_ref");
        assertEquals("1", row.get("chunk_index"));

        ArgumentCaptor<LlmAuditRequest> audit = ArgumentCaptor.forClass(LlmAuditRequest.class);
        verify(coreGateway, atLeastOnce()).writeAudit(audit.capture());
        LlmAuditRequest last = audit.getValue();
        assertEquals("knowledge_reconcile", last.objectType());
        assertTrue(String.valueOf(last.before()).contains(String.valueOf(docId)), "检出清单留痕");
        assertTrue(String.valueOf(last.after()).contains("repaired"), "修复动作留痕");

        assertTrue(reconcileService.reconcile().clean(), "修复即收敛，重跑零差异");
    }

    /** REQUIREMENT ledger rows are reconciled on the same generic path; the
     * requirement id lives in source_ref and must never leak into case_id
     * (retrieval filters scope by case id). Pins the knowledge-base closure
     * branch for the requirement family. */
    @Test
    void 对账_REQUIREMENT台账缺向量_重嵌入保源类型且case_id归零() {
        long docId = insertLedger("REQUIREMENT", 7101L, "登录需求", "验收标准：两秒内登录成功", "ACTIVE");

        KnowledgeReconcileResult result = reconcileService.reconcile();

        assertTrue(result.repairedDocIds().contains(docId), "需求台账行纳入对账修复");
        assertEquals(1, vectorRowsOf(docId), "按台账 content 重嵌入补一行");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT content, metadata->>'source_type' as source_type,"
                        + " metadata->>'case_id' as case_id, metadata->>'status' as status"
                        + " FROM knowledge.tp_knowledge_chunk WHERE metadata->>'doc_id' = ?",
                String.valueOf(docId));
        assertEquals("验收标准：两秒内登录成功", row.get("content"), "补写内容与台账一致");
        assertEquals("REQUIREMENT", row.get("source_type"), "补写保留需求族源类型");
        assertEquals("0", row.get("case_id"), "需求 id 走 source_ref，禁写进 case_id");
        assertEquals("ACTIVE", row.get("status"));
        assertTrue(reconcileService.reconcile().clean(), "修复即收敛，重跑零差异");
    }

    @Test
    void 对账_orphan向量行台账INACTIVE_删向量保台账() {
        long docId = insertLedger("TEST_SPEC", 777L, "规范文档", "合并正文", "INACTIVE");
        insertVectorRow(docId, PROJECT_ID, "TEST_SPEC", 0L, "合并正文", "INACTIVE");

        KnowledgeReconcileResult result = reconcileService.reconcile();

        assertTrue(result.orphanDeletedDocIds().contains(docId), "向量有台账非 ACTIVE=orphan");
        assertEquals(0, vectorRowsOf(docId), "orphan 向量删除");
        assertEquals("INACTIVE", jdbcTemplate.queryForObject(
                "SELECT status FROM tp_knowledge_doc WHERE id = ?", String.class, docId),
                "台账软下线行保留（重建/审计依据，T177b 定案）");
    }

    @Test
    void 对账_orphan向量行无台账_删除() {
        insertVectorRow(888888L, PROJECT_ID, "TEST_SPEC", 0L, "无主向量", "ACTIVE");

        KnowledgeReconcileResult result = reconcileService.reconcile();

        assertTrue(result.orphanDeletedDocIds().contains(888888L));
        assertEquals(0, vectorRowsOf(888888L));
    }

    @Test
    void 对账_零差异_不修复不动audit() {
        long docId = insertLedger("TEST_CASE", 666L, "在效用例", "正文在效", "ACTIVE");
        insertVectorRow(docId, PROJECT_ID, "TEST_CASE", 666L, "正文在效", "ACTIVE");

        KnowledgeReconcileResult result = reconcileService.reconcile();

        assertTrue(result.clean(), "一致态零动作");
        assertEquals(1, vectorRowsOf(docId), "一致态禁误删误插");
        verify(coreGateway, never()).writeAudit(any());
    }

    @Test
    void 全量重建_台账ACTIVE全量先删后插_与台账零差异() {
        long spec = insertLedger("TEST_SPEC", 901L, "规范A", "规范正文A", "ACTIVE");
        long caze = insertLedger("TEST_CASE", 902L, "用例B", "用例如文B", "ACTIVE");
        insertVectorRow(spec, PROJECT_ID, "TEST_SPEC", 0L, "被污染的旧向量", "ACTIVE");
        insertVectorRow(888888L, PROJECT_ID, "TEST_SPEC", 0L, "无主向量", "ACTIVE");

        KnowledgeReconcileResult result = reconcileService.rebuildAll();

        assertTrue(result.repairedDocIds().containsAll(List.of(spec, caze)),
                "重扫台账 ACTIVE 含本测试两行（全量重建命中）");
        assertTrue(result.orphanDeletedDocIds().contains(888888L));
        Map<String, Object> specRow = jdbcTemplate.queryForMap(
                "SELECT content FROM knowledge.tp_knowledge_chunk WHERE metadata->>'doc_id' = ?",
                String.valueOf(spec));
        assertEquals("规范正文A", specRow.get("content"), "先删后插=与台账 content 一致");
        assertEquals(1, vectorRowsOf(caze));
        assertEquals(1, vectorRowsOf(spec));
        assertEquals(0, vectorRowsOf(888888L));

        assertTrue(reconcileService.reconcile().clean(), "重建↔台账对账 0 差异");
        assertFalse(result.repairedDocIds().isEmpty());
    }
}
