package com.sixbean.testpike.aiservice.knowledge.domain.service;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.knowledge.domain.component.FakeEmbeddingModel;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeChunkMetadataUpdater;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeMetadataFields;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeVectorStoreTestConfig;
import com.sixbean.testpike.common.mq.AiKbIngestMessage;
import com.sixbean.testpike.common.mq.CaseIngestEventMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;


@SpringBootTest
@Import(KnowledgeVectorStoreTestConfig.class)
class KnowledgeIngestServiceTest {

    
    private static final long PROJECT_ID = 987_654_321L;
    private static final long DOCUMENT_ID = 987_000_001L;

    @Autowired
    private KnowledgeIngestService ingestService;

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
        jdbcTemplate.update("DELETE FROM public.tp_knowledge_doc WHERE project_id = ?", PROJECT_ID);
    }

    private void stubChunks(List<Map<String, Object>> chunks) {
        when(coreGateway.getDocumentChunks(DOCUMENT_ID)).thenReturn(chunks);
        when(coreGateway.getDocument(DOCUMENT_ID)).thenReturn(
                Map.of("id", DOCUMENT_ID, "projectId", PROJECT_ID, "name", "登录模块测试规范"));
        when(coreGateway.getProject(anyLong()))
                .thenReturn(new CoreGateway.ProjectBrief(PROJECT_ID, "kb-test", "zh-CN", 1));
    }

    private AiKbIngestMessage message() {
        return new AiKbIngestMessage(DOCUMENT_ID, PROJECT_ID, 1L, 1L, null);
    }


    private static final long REQUIREMENT_ID = 987_000_050L;

    private void stubRequirement(String acceptanceCriteria) {
        when(coreGateway.getRequirement(REQUIREMENT_ID)).thenReturn(
                new CoreGateway.RequirementBrief(REQUIREMENT_ID, "登录验证码需求", acceptanceCriteria));
        when(coreGateway.getProject(anyLong()))
                .thenReturn(new CoreGateway.ProjectBrief(PROJECT_ID, "kb-test", "zh-CN", 1));
    }

    private CaseIngestEventMessage requirementMessage(String eventType, int version) {
        return new CaseIngestEventMessage(eventType, null, PROJECT_ID, 1L, null,
                null, null, null, null, 1_700_000_000_000L, null, REQUIREMENT_ID, version);
    }

    @Test
    void 需求入库_台账REQUIREMENT在位_验收标准E5脱敏() {
        stubRequirement("手机号 13812345678 收取验证码，邮箱 zhangsan@example.com 通知");

        ingestService.ingestRequirement(requirementMessage("REVIEW_PASSED", 1));

        String ledgerType = jdbcTemplate.queryForObject(
                "select source_type from tp_knowledge_doc where project_id = ? and source_ref = ?",
                String.class, PROJECT_ID, REQUIREMENT_ID);
        assertEquals("REQUIREMENT", ledgerType, "台账来源类型=REQUIREMENT（14 §2 来源族新增）");
        String content = jdbcTemplate.queryForObject(
                "select content from tp_knowledge_doc where project_id = ? and source_ref = ?",
                String.class, PROJECT_ID, REQUIREMENT_ID);
        assertTrue(content.contains("138****5678"), "验收标准 E5 入库前脱敏（IT-EMB-03 同规则）");
        assertFalse(content.contains("13812345678"), "原文手机号不得残留");
        Integer vectorRows = jdbcTemplate.queryForObject(
                "select count(*) from knowledge.tp_knowledge_chunk where metadata->>'project_id' = ?"
                        + " and metadata->>'source_type' = 'REQUIREMENT'",
                Integer.class, String.valueOf(PROJECT_ID));
        assertEquals(1, vectorRows, "整条需求=1 向量 chunk（14 §3.2 粒度表同用例基径）");
    }

    @Test
    void 需求重入库_台账不增行内容保最新_向量先删后插() {
        stubRequirement("第一版验收标准");
        ingestService.ingestRequirement(requirementMessage("REVIEW_PASSED", 1));
        Long firstId = jdbcTemplate.queryForObject(
                "select id from tp_knowledge_doc where project_id = ? and source_ref = ?",
                Long.class, PROJECT_ID, REQUIREMENT_ID);

        stubRequirement("第二版验收标准 更细");
        ingestService.ingestRequirement(requirementMessage("REVIEW_PASSED", 2));

        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from tp_knowledge_doc where project_id = ? and source_ref = ?",
                Integer.class, PROJECT_ID, REQUIREMENT_ID);
        assertEquals(1, rows, "同来源重入库台账不增行（upsert 单源）");
        String latest = jdbcTemplate.queryForObject(
                "select content from tp_knowledge_doc where id = ?", String.class, firstId);
        assertTrue(latest.contains("第二版"), "重入库保最新（先删后插 §6.1）");
        Integer revision = jdbcTemplate.queryForObject(
                "select revision from tp_knowledge_doc where id = ?", Integer.class, firstId);
        assertEquals(2, revision, "内容改写推进 revision（V339 引用可点名版本）");
        Integer vectorRows = jdbcTemplate.queryForObject(
                "select count(*) from knowledge.tp_knowledge_chunk where metadata->>'doc_id' = ?",
                Integer.class, String.valueOf(firstId));
        assertEquals(1, vectorRows, "向量按 doc_id 先删后插恒一行（残留=缺陷）");
    }

    @Test
    void 需求下线_台账INACTIVE向量删净() {
        stubRequirement("待下线验收标准");
        ingestService.ingestRequirement(requirementMessage("REVIEW_PASSED", 1));
        Long docId = jdbcTemplate.queryForObject(
                "select id from tp_knowledge_doc where project_id = ? and source_ref = ?",
                Long.class, PROJECT_ID, REQUIREMENT_ID);

        ingestService.retireRequirement(REQUIREMENT_ID);

        String status = jdbcTemplate.queryForObject(
                "select status from tp_knowledge_doc where id = ?", String.class, docId);
        assertEquals("INACTIVE", status, "下线=台账软下线保行（审计/重建依据同 cleanupCase 基径）");
        Integer vectorRows = jdbcTemplate.queryForObject(
                "select count(*) from knowledge.tp_knowledge_chunk where metadata->>'doc_id' = ?",
                Integer.class, String.valueOf(docId));
        assertEquals(0, vectorRows, "下线=向量物理删净（检索不可达，封 AI 自污染环）");
    }

    @Test
    void 需求下线未入库_天然幂等零动作() {
        ingestService.retireRequirement(987_000_099L);

        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from tp_knowledge_doc where project_id = ? and source_ref = ?",
                Integer.class, PROJECT_ID, 987_000_099L);
        assertEquals(0, rows, "无台账行零写入");
    }

    
    @Test
    void ddlDict分类消息_台账与向量source_type透传() {
        stubChunks(List.of(Map.of("chapter", "订单库", "seq", 1,
                "content", "orders 表 status 列枚举 0=待支付")));

        ingestService.ingest(new AiKbIngestMessage(DOCUMENT_ID, PROJECT_ID, 1L, 1L, "DDL_DICT"));

        String ledgerType = jdbcTemplate.queryForObject(
                "select source_type from tp_knowledge_doc where project_id = ? and source_ref = ?",
                String.class, PROJECT_ID, DOCUMENT_ID);
        assertEquals("DDL_DICT", ledgerType, "台账来源类型透传（非硬编 TEST_SPEC）");
        String vectorType = jdbcTemplate.queryForObject(
                "select metadata->>'source_type' from knowledge.tp_knowledge_chunk"
                        + " where metadata->>'project_id' = ?",
                String.class, String.valueOf(PROJECT_ID));
        assertEquals("DDL_DICT", vectorType, "向量 metadata 透传");
    }

    
    @Test
    void category空_回退TEST_SPEC零漂移() {
        stubChunks(List.of(Map.of("chapter", "登录", "seq", 1, "content", "错误提示须为人话中文")));

        ingestService.ingest(message());

        String ledgerType = jdbcTemplate.queryForObject(
                "select source_type from tp_knowledge_doc where project_id = ? and source_ref = ?",
                String.class, PROJECT_ID, DOCUMENT_ID);
        assertEquals("TEST_SPEC", ledgerType, "null=回退 TEST_SPEC（旧消息兼容）");
    }

    
    @Test
    void requirementDoc分类消息_归位TEST_SPEC台账值域零扩() {
        stubChunks(List.of(Map.of("chapter", "订单需求", "seq", 1,
                "content", "下单后 30 分钟未支付自动关闭")));

        ingestService.ingest(new AiKbIngestMessage(DOCUMENT_ID, PROJECT_ID, 1L, 1L,
                "REQUIREMENT_DOC"));

        String ledgerType = jdbcTemplate.queryForObject(
                "select source_type from tp_knowledge_doc where project_id = ? and source_ref = ?",
                String.class, PROJECT_ID, DOCUMENT_ID);
        assertEquals("TEST_SPEC", ledgerType,
                "需求文档与测试规格说明共享同一检索面（归位映射，非未知回退）");
        String vectorType = jdbcTemplate.queryForObject(
                "select metadata->>'source_type' from knowledge.tp_knowledge_chunk"
                        + " where metadata->>'project_id' = ?",
                String.class, String.valueOf(PROJECT_ID));
        assertEquals("TEST_SPEC", vectorType, "向量 metadata 同侧归位");
    }

    @Test
    void 入库两chunk_台账ACTIVE加向量行十字段metadata齐() {
        stubChunks(List.of(
                Map.of("chapter", "登录模块", "seq", 1, "content", "接口命名须用动宾结构"),
                Map.of("chapter", "登录模块", "seq", 2, "content", "错误提示须为人话中文")));

        ingestService.ingest(message());

        Integer ledgerRows = jdbcTemplate.queryForObject(
                "select count(*) from tp_knowledge_doc where project_id = ? and source_ref = ?"
                        + " and source_type = 'TEST_SPEC' and status = 'ACTIVE'",
                Integer.class, PROJECT_ID, DOCUMENT_ID);
        assertEquals(1, ledgerRows, "台账唯一入口行（14 §1；uq_kb_doc_entity 键形状）");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select content, metadata->>'doc_id' as doc_id,"
                        + " metadata->>'chunk_index' as chunk_index,"
                        + " metadata->>'title' as title, metadata->>'lang' as lang,"
                        + " metadata->>'source_type' as source_type,"
                        + " metadata->>'status' as status, metadata->>'excellent' as excellent,"
                        + " metadata->>'pack_id' as pack_id, metadata->>'case_id' as case_id"
                        + " from knowledge.tp_knowledge_chunk"
                        + " where metadata->>'project_id' = ?"
                        + " order by (metadata->>'chunk_index')::int",
                String.valueOf(PROJECT_ID));
        assertEquals(2, rows.size(), "两分块两向量行（IT-VEC-04 分块边界=复用 F1 块）");
        Long ledgerId = jdbcTemplate.queryForObject(
                "select id from tp_knowledge_doc where project_id = ?", Long.class, PROJECT_ID);
        Map<String, Object> meta = rows.get(0);
        assertEquals(String.valueOf(ledgerId), meta.get("doc_id"),
                "向量 doc_id=台账关联（14 §4.3，非 tp_document.id）");
        assertEquals("1", meta.get("chunk_index"));
        assertEquals("登录模块", meta.get("title"));
        assertEquals("zh-CN", meta.get("lang"), "lang=项目默认语言（14 §4.3/E12）");
        assertEquals("TEST_SPEC", meta.get("source_type"));
        assertEquals(KnowledgeMetadataFields.ACTIVE, meta.get("status"));
        assertEquals("false", meta.get("excellent"), "文档来源 excellent 恒 false");
        assertEquals("0", meta.get("pack_id"));
        assertEquals("0", meta.get("case_id"));

        List<org.springframework.ai.document.Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("命名")
                        .filterExpression("project_id == " + PROJECT_ID
                                + " && status == '" + KnowledgeMetadataFields.ACTIVE + "'")
                        .topK(10).build());
        assertEquals(2, hits.size(), "filterExpression 检索可达（写路径与 T178 过滤链路键一致）");
    }

    @Test
    void 手机号与邮箱入库前逐块脱敏E5() {
        stubChunks(List.of(Map.of("chapter", "联系人", "seq", 1,
                "content", "如有疑问联系 13812345678 或 zhangsan@example.com")));

        ingestService.ingest(message());

        String content = jdbcTemplate.queryForObject(
                "select content from knowledge.tp_knowledge_chunk where metadata->>'project_id' = ?",
                String.class, String.valueOf(PROJECT_ID));
        assertTrue(content.contains("138****5678"), "手机号 E5 脱敏（IT-EMB-03 点名件同规则）");
        assertTrue(content.contains("z***@example.com"), "邮箱 E5 脱敏");
        assertFalse(content.contains("13812345678"), "原文不得残留");
        String ledgerContent = jdbcTemplate.queryForObject(
                "select content from tp_knowledge_doc where project_id = ?", String.class, PROJECT_ID);
        assertFalse(ledgerContent.contains("13812345678"), "台账 content 同为脱敏后（6.6 重建权威）");
    }

    @Test
    void 重复入库台账不增行向量先删后插保最新() {
        stubChunks(List.of(Map.of("chapter", "登录模块", "seq", 1, "content", "旧内容")));
        ingestService.ingest(message());

        stubChunks(List.of(Map.of("chapter", "登录模块", "seq", 1, "content", "新内容升级")));
        ingestService.ingest(message());

        Integer ledgerRows = jdbcTemplate.queryForObject(
                "select count(*) from tp_knowledge_doc where project_id = ?", Integer.class, PROJECT_ID);
        assertEquals(1, ledgerRows, "同文档重入不产生第二台账行（14 §6.1 先删后插保最新）");
        String ledgerContent = jdbcTemplate.queryForObject(
                "select content from tp_knowledge_doc where project_id = ?", String.class, PROJECT_ID);
        assertEquals("新内容升级", ledgerContent, "台账 content 覆盖为最新");

        List<Map<String, Object>> vectorRows = jdbcTemplate.queryForList(
                "select content from knowledge.tp_knowledge_chunk where metadata->>'project_id' = ?",
                String.valueOf(PROJECT_ID));
        assertEquals(1, vectorRows.size(), "旧向量行清除，零残留");
        assertEquals("新内容升级", vectorRows.get(0).get("content"));
    }

    @Test
    void 零分块文档入库无操作不炸() {
        stubChunks(List.of());
        ingestService.ingest(message());
        Integer ledgerRows = jdbcTemplate.queryForObject(
                "select count(*) from tp_knowledge_doc where project_id = ?", Integer.class, PROJECT_ID);
        assertEquals(0, ledgerRows, "无块可入=零动作（文档尚非终态，等待重投即可）");
    }


    private static final long CASE_ID = 987_000_002L;

    private void stubCase(Map<String, Object> detail, List<Map<String, Object>> tags) {
        when(coreGateway.getCase(CASE_ID)).thenReturn(detail);
        when(coreGateway.getCaseTags(CASE_ID)).thenReturn(tags);
        when(coreGateway.getProject(anyLong()))
                .thenReturn(new CoreGateway.ProjectBrief(PROJECT_ID, "kb-test", "zh-CN", 1));
    }

    private CaseIngestEventMessage caseMessage(int version) {
        return new CaseIngestEventMessage("REVIEW_CHANGED", CASE_ID, PROJECT_ID, 1L, "PASS",
                null, null, null, null, 1_700_000_000_000L, version);
    }

    private Map<String, Object> caseDetail() {
        return Map.of("id", CASE_ID, "caseNo", "TC-LOGIN-001", "projectId", PROJECT_ID,
                "title", "登录成功用例", "precondition", "用户已注册",
                "expectedResult", "跳转首页", "version", 3,
                "steps", List.of(
                        Map.of("id", 1L, "stepNo", 1, "description", "输入账号 admin", "isAssert", 0),
                        Map.of("id", 2L, "stepNo", 2, "description", "提示欢迎回来", "isAssert", 1)));
    }

    @Test
    void 用例整条入库_台账TEST_CASE加单chunk十字段含标签步骤() {
        stubCase(caseDetail(), List.of(Map.of("id", 10L, "name", "冒烟"),
                Map.of("id", 11L, "name", "登录")));

        ingestService.ingestCase(caseMessage(3));

        Integer ledgerRows = jdbcTemplate.queryForObject(
                "select count(*) from tp_knowledge_doc where project_id = ? and source_ref = ?"
                        + " and source_type = 'TEST_CASE' and status = 'ACTIVE'",
                Integer.class, PROJECT_ID, CASE_ID);
        assertEquals(1, ledgerRows, "用例台账行（uq_kb_doc_entity 键=TEST_CASE+project+caseId）");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select content, metadata->>'doc_id' as doc_id,"
                        + " metadata->>'case_id' as case_id,"
                        + " metadata->>'chunk_index' as chunk_index,"
                        + " metadata->>'source_type' as source_type,"
                        + " metadata->>'excellent' as excellent,"
                        + " metadata->>'status' as status"
                        + " from knowledge.tp_knowledge_chunk"
                        + " where metadata->>'project_id' = ?",
                String.valueOf(PROJECT_ID));
        assertEquals(1, rows.size(), "14 §3.2 粒度表：用例整条=1 chunk");
        Map<String, Object> row = rows.get(0);
        assertEquals(String.valueOf(CASE_ID), row.get("case_id"));
        assertEquals("1", row.get("chunk_index"));
        assertEquals("TEST_CASE", row.get("source_type"));
        assertEquals("false", row.get("excellent"), "新入库 excellent 初值 false（D3 口径）");
        String content = String.valueOf(row.get("content"));
        assertTrue(content.contains("输入账号 admin"), "步骤文本入 chunk");
        assertTrue(content.contains("提示欢迎回来（断言）") || content.contains("断言"),
                "断言步骤标注");
        assertTrue(content.contains("冒烟、登录"), "标签拼装（getCaseTags 链路，非降级）");
    }

    @Test
    void 用例重复入库先删后插保最新不增行() {
        stubCase(caseDetail(), List.of(Map.of("id", 10L, "name", "冒烟")));
        ingestService.ingestCase(caseMessage(3));

        Map<String, Object> edited = new java.util.HashMap<>(caseDetail());
        edited.put("title", "登录成功用例V2");
        edited.put("steps", List.of(Map.of("id", 1L, "stepNo", 1, "description", "新内容步骤", "isAssert", 0)));
        stubCase(edited, List.of());
        ingestService.ingestCase(caseMessage(4));

        Integer ledgerRows = jdbcTemplate.queryForObject(
                "select count(*) from tp_knowledge_doc where project_id = ?", Integer.class, PROJECT_ID);
        assertEquals(1, ledgerRows, "同用例新版本重入台账不增行");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select content from knowledge.tp_knowledge_chunk where metadata->>'project_id' = ?",
                String.valueOf(PROJECT_ID));
        assertEquals(1, rows.size(), "旧向量行清除（14 §6.1 先删后插保最新已通过版）");
        assertTrue(String.valueOf(rows.get(0).get("content")).contains("新内容步骤"));
    }

    @Test
    void 用例正文手机号入库前脱敏E5() {
        Map<String, Object> detail = new java.util.HashMap<>(caseDetail());
        detail.put("precondition", "测试账号 13812345678 已注册");
        stubCase(detail, List.of());

        ingestService.ingestCase(caseMessage(3));

        String content = jdbcTemplate.queryForObject(
                "select content from knowledge.tp_knowledge_chunk where metadata->>'project_id' = ?",
                String.class, String.valueOf(PROJECT_ID));
        assertTrue(content.contains("138****5678"), "E5 脱敏前置（IT-EMB-03 同规则）");
        assertFalse(content.contains("13812345678"), "原文不得残留");
    }

    @Test
    void 标签链路不可达降级空标签不阻断入库() {
        when(coreGateway.getCase(CASE_ID)).thenReturn(caseDetail());
        when(coreGateway.getCaseTags(CASE_ID)).thenThrow(new IllegalStateException("core 不可达"));
        when(coreGateway.getProject(anyLong()))
                .thenReturn(new CoreGateway.ProjectBrief(PROJECT_ID, "kb-test", "zh-CN", 1));

        ingestService.ingestCase(caseMessage(3));

        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from knowledge.tp_knowledge_chunk where metadata->>'project_id' = ?",
                Integer.class, String.valueOf(PROJECT_ID));
        assertEquals(1, rows, "标签取数失败=降级空标签，入库主链不阻断（warn 不静默）");
    }

    @Test
    void 优秀标记直改metadata不重入向量化() {
        stubCase(caseDetail(), List.of(Map.of("id", 10L, "name", "冒烟")));
        ingestService.ingestCase(caseMessage(3));

        KnowledgeChunkMetadataUpdater updater =
                new KnowledgeChunkMetadataUpdater(new NamedParameterJdbcTemplate(jdbcTemplate));
        int updated = updater.updateCaseMetadata(
                KnowledgeMetadataFields.EXCELLENT, "true", CASE_ID);
        assertEquals(1, updated, "excellent 直改命中 1 行");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select content, metadata->>'excellent' as excellent"
                        + " from knowledge.tp_knowledge_chunk where metadata->>'case_id' = ?",
                String.valueOf(CASE_ID));
        assertEquals("true", row.get("excellent"));
        assertTrue(String.valueOf(row.get("content")).contains("输入账号 admin"),
                "TC-G1-02：仅 metadata 更新，content 未重入向量（不产生第二行）");

        Integer rowCount = jdbcTemplate.queryForObject(
                "select count(*) from knowledge.tp_knowledge_chunk where metadata->>'project_id' = ?",
                Integer.class, String.valueOf(PROJECT_ID));
        assertEquals(1, rowCount, "直改不新增向量行");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> updater.updateCaseMetadata("title", "\"x\"", CASE_ID),
                "非白名单键拒绝（防任意 metadata 注入）");
    }


    private Map<String, Object> caseDetailWith(int excellent, int status) {
        Map<String, Object> detail = new java.util.HashMap<>(caseDetail());
        detail.put("excellent", excellent);
        detail.put("status", status);
        return detail;
    }

    private String excellentMetadataValue() {
        return jdbcTemplate.queryForObject(
                "select metadata->>'excellent' from knowledge.tp_knowledge_chunk where metadata->>'case_id' = ?",
                String.class, String.valueOf(CASE_ID));
    }

    @Test
    void 已通过已标优秀入库_Factory带真值excellent为true() {
        stubCase(caseDetailWith(1, 3), List.of());
        ingestService.ingestCase(caseMessage(3));

        assertEquals("true", excellentMetadataValue(), "已通过+已标优秀入库=metadata 带真值进（14 §2 ②）");
    }

    @Test
    void 标记联动_台账ACTIVE直改metadata不重入_取消同样直改false() {
        stubCase(caseDetailWith(0, 3), List.of());
        ingestService.ingestCase(caseMessage(3));

        stubCase(caseDetailWith(1, 3), List.of());
        ingestService.applyExcellentFlag(new CaseIngestEventMessage("REVIEW_CHANGED", CASE_ID,
                PROJECT_ID, 1L, "EXCELLENT", null, null, null, null, 1_700_000_000_000L, 4));

        assertEquals("true", excellentMetadataValue(), "ACTIVE 台账命中=jsonb_set 直改（TC-G1-02 不重入）");
        Integer vectorRows = jdbcTemplate.queryForObject(
                "select count(*) from knowledge.tp_knowledge_chunk where metadata->>'case_id' = ?",
                Integer.class, String.valueOf(CASE_ID));
        assertEquals(1, vectorRows, "直改不产生第二向量行");

        stubCase(caseDetailWith(0, 3), List.of());
        ingestService.applyExcellentFlag(new CaseIngestEventMessage("REVIEW_CHANGED", CASE_ID,
                PROJECT_ID, 1L, "EXCELLENT", null, null, null, null, 1_700_000_000_000L, 5));
        assertEquals("false", excellentMetadataValue(), "取消=false 同链路直改回 false（16 行「取消=false」）");
    }

    @Test
    void 标记联动_无ACTIVE台账且详情已通过_兜底重入库带真值() {
        stubCase(caseDetailWith(0, 3), List.of());
        ingestService.ingestCase(caseMessage(3));
        ingestService.cleanupCase(CASE_ID);

        stubCase(caseDetailWith(1, 3), List.of());
        ingestService.applyExcellentFlag(new CaseIngestEventMessage("REVIEW_CHANGED", CASE_ID,
                PROJECT_ID, 1L, "EXCELLENT", null, null, null, null, 1_700_000_000_000L, 4));

        String ledgerStatus = jdbcTemplate.queryForObject(
                "select status from tp_knowledge_doc where source_type = 'TEST_CASE' and source_ref = ?",
                String.class, CASE_ID);
        assertEquals("ACTIVE", ledgerStatus, "兜底重入库复活台账（G 卡台账探测链路）");
        Integer vectorRows = jdbcTemplate.queryForObject(
                "select count(*) from knowledge.tp_knowledge_chunk where metadata->>'case_id' = ?",
                Integer.class, String.valueOf(CASE_ID));
        assertEquals(1, vectorRows, "兜底重入库向量恢复");
        assertEquals("true", excellentMetadataValue(), "兜底链路同带真值进（Factory 参=详情 excellent）");
    }

    @Test
    void 标记联动_无台账且非已通过_跳过零动作() {
        stubCase(caseDetailWith(1, 1), List.of());
        ingestService.applyExcellentFlag(new CaseIngestEventMessage("REVIEW_CHANGED", CASE_ID,
                PROJECT_ID, 1L, "EXCELLENT", null, null, null, null, 1_700_000_000_000L, 2));

        Integer ledgerRows = jdbcTemplate.queryForObject(
                "select count(*) from tp_knowledge_doc where project_id = ?", Integer.class, PROJECT_ID);
        assertEquals(0, ledgerRows, "非已通过未入库=无可联动对象，log 跳过禁兜底入库");
    }

    @Test
    void T217_开关关_无ACTIVE台账已通过_兜底链路受闸禁入库() {
        stubCase(caseDetailWith(1, 3), List.of());
        when(coreGateway.getProject(anyLong()))
                .thenReturn(new CoreGateway.ProjectBrief(PROJECT_ID, "kb-test", "zh-CN", 0));
        ingestService.applyExcellentFlag(new CaseIngestEventMessage("REVIEW_CHANGED", CASE_ID,
                PROJECT_ID, 1L, "EXCELLENT", null, null, null, null, 1_700_000_000_000L, 3));

        Integer ledgerRows = jdbcTemplate.queryForObject(
                "select count(*) from tp_knowledge_doc where project_id = ?", Integer.class, PROJECT_ID);
        assertEquals(0, ledgerRows, "关态无行受闸禁入库（防旁路）");
    }

    @Test
    void T217_开关关_ACTIVE行存在_metadata照更新可摘历史标() {
        stubCase(caseDetailWith(0, 3), List.of());
        ingestService.ingestCase(caseMessage(3));
        when(coreGateway.getProject(anyLong()))
                .thenReturn(new CoreGateway.ProjectBrief(PROJECT_ID, "kb-test", "zh-CN", 0));

        stubCase(caseDetailWith(1, 3), List.of());
        ingestService.applyExcellentFlag(new CaseIngestEventMessage("REVIEW_CHANGED", CASE_ID,
                PROJECT_ID, 1L, "EXCELLENT", null, null, null, null, 1_700_000_000_000L, 4));

        assertEquals("true", excellentMetadataValue(), "关态 ACTIVE 行 metadata 照更新（历史标可摘）");
    }


    @Test
    void 用例清理_向量行删除加台账INACTIVE保内容() {
        stubCase(caseDetail(), List.of(Map.of("id", 10L, "name", "冒烟")));
        ingestService.ingestCase(caseMessage(3));

        ingestService.cleanupCase(CASE_ID);

        Integer vectorRows = jdbcTemplate.queryForObject(
                "select count(*) from knowledge.tp_knowledge_chunk where metadata->>'case_id' = ?",
                Integer.class, String.valueOf(CASE_ID));
        assertEquals(0, vectorRows, "离开已通过即清理向量（14 §6.2）");
        Map<String, Object> ledger = jdbcTemplate.queryForMap(
                "select status, content from tp_knowledge_doc where project_id = ?"
                        + " and source_type = 'TEST_CASE' and source_ref = ?",
                PROJECT_ID, CASE_ID);
        assertEquals("INACTIVE", ledger.get("status"), "台账软下线保行（重建/审计依据）");
        assertTrue(String.valueOf(ledger.get("content")).contains("输入账号 admin"),
                "台账 content 保留（T177c 重建链路纯台账驱动）");
    }

    @Test
    void 用例清理后重新入库_台账复活ACTIVE向量恢复() {
        stubCase(caseDetail(), List.of());
        ingestService.ingestCase(caseMessage(3));
        ingestService.cleanupCase(CASE_ID);

        ingestService.ingestCase(caseMessage(4));

        String status = jdbcTemplate.queryForObject(
                "select status from tp_knowledge_doc where project_id = ?"
                        + " and source_type = 'TEST_CASE' and source_ref = ?",
                String.class, PROJECT_ID, CASE_ID);
        assertEquals("ACTIVE", status, "重入库复活台账（14 §6.1 保最新已通过版）");
        Integer vectorRows = jdbcTemplate.queryForObject(
                "select count(*) from knowledge.tp_knowledge_chunk where metadata->>'case_id' = ?",
                Integer.class, String.valueOf(CASE_ID));
        assertEquals(1, vectorRows);
    }

    @Test
    void 无台账无向量的用例清理_零动作不炸() {
        ingestService.cleanupCase(987_999_999L);

        Integer ledgerRows = jdbcTemplate.queryForObject(
                "select count(*) from tp_knowledge_doc where project_id = ?",
                Integer.class, PROJECT_ID);
        assertEquals(0, ledgerRows);
    }


    @Autowired
    private EmbeddingModel embeddingModel;

    private FakeEmbeddingModel fakeModel() {
        return (FakeEmbeddingModel) embeddingModel;
    }

    @Test
    void IT_VEC_03_批内embedding失败_台账不置ACTIVE旧向量不丢() {
        stubChunks(List.of(Map.of("chapter", "登录模块", "seq", 1, "content", "旧内容基线")));
        ingestService.ingest(message());

        FakeEmbeddingModel holder = fakeModel();
        try {
            holder.failOnMarker = "批内失败标记";
            stubChunks(List.of(
                    Map.of("chapter", "登录模块", "seq", 1, "content", "新内容甲"),
                    Map.of("chapter", "登录模块", "seq", 2, "content", "新内容乙批内失败标记")));
            org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> ingestService.ingest(message()),
                    "批内 embedding 失败必须向上抛出（MQ requeue/DLX 重投依据，禁静默吞）");

            Map<String, Object> ledger = jdbcTemplate.queryForMap(
                    "select status, content from tp_knowledge_doc where project_id = ?", PROJECT_ID);
            assertEquals("ACTIVE", ledger.get("status"), "既有台账行状态不因失败轮漂移");
            assertEquals("旧内容基线", ledger.get("content"),
                    "台账 content 不得半写为新内容（18 IT-VEC-03：批不半写）");
            List<Map<String, Object>> vectorRows = jdbcTemplate.queryForList(
                    "select content from knowledge.tp_knowledge_chunk where metadata->>'project_id' = ?",
                    String.valueOf(PROJECT_ID));
            assertEquals(1, vectorRows.size(), "旧向量行不得在失败轮被删光（先删后插必须与写入同原子域）");
            assertEquals("旧内容基线", vectorRows.get(0).get("content"));
        } finally {
            holder.failOnMarker = null;
        }
    }

    @Test
    void IT_VEC_03_失败后重试整批收敛幂等() {
        stubChunks(List.of(Map.of("chapter", "登录模块", "seq", 1, "content", "旧内容基线")));
        ingestService.ingest(message());

        FakeEmbeddingModel holder = fakeModel();
        try {
            holder.failOnMarker = "批内失败标记";
            stubChunks(List.of(
                    Map.of("chapter", "登录模块", "seq", 1, "content", "新内容甲"),
                    Map.of("chapter", "登录模块", "seq", 2, "content", "带批内失败标记的块")));
            org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> ingestService.ingest(message()));
        } finally {
            holder.failOnMarker = null;
        }

        stubChunks(List.of(
                Map.of("chapter", "登录模块", "seq", 1, "content", "新内容甲"),
                Map.of("chapter", "登录模块", "seq", 2, "content", "修复后的块")))
        ;
        ingestService.ingest(message());

        Integer ledgerRows = jdbcTemplate.queryForObject(
                "select count(*) from tp_knowledge_doc where project_id = ?", Integer.class, PROJECT_ID);
        assertEquals(1, ledgerRows, "重试整批收敛：台账不增行（幂等键链路不变）");
        String ledgerContent = jdbcTemplate.queryForObject(
                "select content from tp_knowledge_doc where project_id = ?", String.class, PROJECT_ID);
        assertTrue(ledgerContent.contains("新内容甲") && ledgerContent.contains("修复后的块"),
                "台账=最新整批内容");
        List<Map<String, Object>> vectorRows = jdbcTemplate.queryForList(
                "select content from knowledge.tp_knowledge_chunk where metadata->>'project_id' = ?"
                        + " order by (metadata->>'chunk_index')::int",
                String.valueOf(PROJECT_ID));
        assertEquals(2, vectorRows.size(), "重试整批写入=恰好 2 行零残留");
        assertEquals("新内容甲", vectorRows.get(0).get("content"));
        assertEquals("修复后的块", vectorRows.get(1).get("content"));
    }

    
    @Test
    void 项目级清理_两链路台账全INACTIVE向量全删() {
        stubChunks(List.of(Map.of("chapter", "登录模块", "seq", 1, "content", "接口命名规范")));
        ingestService.ingest(message());
        stubCase(caseDetail(), List.of());
        ingestService.ingestCase(caseMessage(3));

        ingestService.cleanupProject(PROJECT_ID);

        Integer vectorRows = jdbcTemplate.queryForObject(
                "select count(*) from knowledge.tp_knowledge_chunk where metadata->>'project_id' = ?",
                Integer.class, String.valueOf(PROJECT_ID));
        assertEquals(0, vectorRows, "项目级清理=文档链路+用例链路向量全删");
        Integer activeRows = jdbcTemplate.queryForObject(
                "select count(*) from tp_knowledge_doc where project_id = ? and status = 'ACTIVE'",
                Integer.class, PROJECT_ID);
        assertEquals(0, activeRows, "台账两链路全软下线");
        Integer ledgerRows = jdbcTemplate.queryForObject(
                "select count(*) from tp_knowledge_doc where project_id = ?", Integer.class, PROJECT_ID);
        assertEquals(2, ledgerRows, "软下线保留行供重建/审计");
    }

    /**
 * Ledger revision counter : the first ingest seeds
 * revision 1 and re-ingesting the same source must bump the UPDATE
 * branch by exactly one, so a citation can name the version it stood on.
 */
    @Test
    void 重复入库台账revision从1递增到2() {
        stubChunks(List.of(Map.of("chapter", "登录", "seq", 1,
                "content", "接口命名须用动宾结构")));

        ingestService.ingest(message());
        Integer first = jdbcTemplate.queryForObject(
                "select revision from tp_knowledge_doc where project_id = ? and source_ref = ?",
                Integer.class, PROJECT_ID, DOCUMENT_ID);

        ingestService.ingest(message());
        Integer second = jdbcTemplate.queryForObject(
                "select revision from tp_knowledge_doc where project_id = ? and source_ref = ?",
                Integer.class, PROJECT_ID, DOCUMENT_ID);

        assertEquals(1, first, "first ingest seeds revision 1");
        assertEquals(2, second, "re-ingest must bump the ledger revision (UPDATE branch +1)");
    }
}
