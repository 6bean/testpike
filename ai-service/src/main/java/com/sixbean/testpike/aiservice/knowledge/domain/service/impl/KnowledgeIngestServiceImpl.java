package com.sixbean.testpike.aiservice.knowledge.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeChunkDocumentFactory;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeChunkMetadataUpdater;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeLangResolver;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeMetadataFields;
import com.sixbean.testpike.aiservice.knowledge.domain.component.ProjectKbGate;
import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeIngestService;
import com.sixbean.testpike.aiservice.llm.domain.component.EmbeddingBatchSizeResolver;
import com.sixbean.testpike.common.mq.AiKbIngestMessage;
import com.sixbean.testpike.common.mq.CaseIngestEventMessage;
import com.sixbean.testpike.common.util.DesensitizeUtils;
import com.sixbean.testpike.common.util.ListPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Service
public class KnowledgeIngestServiceImpl implements KnowledgeIngestService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestServiceImpl.class);

    
    static final String SOURCE_TYPE_DOC = KnowledgeMetadataFields.SOURCE_TYPE_TEST_SPEC;

    /**
 * Message category -> knowledge ledger source type ( + BA batch
 * decision 9). DDL_DICT passes through unchanged (ledger/vector
 * source_type=DDL_DICT, consumed by the DdlDictBridge prompt path);
 * REQUIREMENT_DOC (the requirement source doc) lands on TEST_SPEC -- the
 * requirement doc and the test spec share one downstream retrieval
 * surface, so the ledger value set is deliberately NOT extended and no
 * second mapping component may exist (single source: this method).
 * Everything else (TEST_SPEC / null = legacy messages) falls back to
 * TEST_SPEC, unknown categories additionally warn by name -- zero
 * semantic drift.
 */
    static String resolveSourceType(String category) {
        String normalized = category == null ? "" : category.trim();
        if (KnowledgeMetadataFields.SOURCE_TYPE_DDL_DICT.equalsIgnoreCase(normalized)) {
            return KnowledgeMetadataFields.SOURCE_TYPE_DDL_DICT;
        }
        if ("REQUIREMENT_DOC".equalsIgnoreCase(normalized)) {
            return SOURCE_TYPE_DOC;
        }
        if (category != null && !category.isBlank()
                && !KnowledgeMetadataFields.SOURCE_TYPE_TEST_SPEC.equalsIgnoreCase(category)) {
            log.warn("G1 入库未知文档分类 category={}（回退 TEST_SPEC，core 上传闸外来源）", category);
        }
        return SOURCE_TYPE_DOC;
    }

    
    static final String SOURCE_TYPE_CASE = KnowledgeMetadataFields.SOURCE_TYPE_TEST_CASE;

    
    static final String SOURCE_TYPE_REQUIREMENT = KnowledgeMetadataFields.SOURCE_TYPE_REQUIREMENT;

    
    private static final int CASE_STATUS_APPROVED = 3;

    private final CoreGateway coreGateway;
    private final VectorStore vectorStore;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final EmbeddingBatchSizeResolver batchSizeResolver;
    private final KnowledgeLangResolver langResolver;
    private final KnowledgeChunkMetadataUpdater metadataUpdater;
    private final ProjectKbGate kbGate;

    public KnowledgeIngestServiceImpl(CoreGateway coreGateway, VectorStore vectorStore,
                                      NamedParameterJdbcTemplate jdbcTemplate,
                                      EmbeddingBatchSizeResolver batchSizeResolver,
                                      KnowledgeLangResolver langResolver,
                                      KnowledgeChunkMetadataUpdater metadataUpdater,
                                      ProjectKbGate kbGate) {
        this.coreGateway = coreGateway;
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.batchSizeResolver = batchSizeResolver;
        this.langResolver = langResolver;
        this.metadataUpdater = metadataUpdater;
        this.kbGate = kbGate;
    }

    @Override
    @Transactional
    public void ingest(AiKbIngestMessage message) {
        List<Map<String, Object>> chunks = coreGateway.getDocumentChunks(message.documentId());
        if (chunks == null || chunks.isEmpty()) {
            log.info("G1 入库跳过：文档无分块 documentId={}（非终态，等待重投）", message.documentId());
            return;
        }
        String docTitle = resolveDocTitle(message.documentId());
        String lang = resolveProjectLang(message.projectId());

        List<String> maskedContents = new ArrayList<>(chunks.size());
        for (Map<String, Object> chunk : chunks) {
            maskedContents.add(DesensitizeUtils.maskText(
                    chunk.get("content") == null ? "" : String.valueOf(chunk.get("content"))));
        }
        String mergedContent = String.join("\n", maskedContents);
        String sourceType = resolveSourceType(message.category());
        long docId = upsertLedger(sourceType, message.projectId(), message.documentId(),
                docTitle, mergedContent);

        vectorStore.delete(KnowledgeMetadataFields.DOC_ID + " == " + docId);

        List<Document> documents = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            documents.add(buildDocument(docId, message.projectId(), chunks.get(i),
                    maskedContents.get(i), docTitle, lang, i, sourceType));
        }
        addInBatches(documents);
        log.info("G1 文档入库完成 documentId={} docId={} chunks={} lang={} sourceType={}",
                message.documentId(), docId, documents.size(), lang, sourceType);
    }

    
    private String resolveDocTitle(long documentId) {
        try {
            Object name = coreGateway.getDocument(documentId).get("name");
            return name == null || String.valueOf(name).isBlank()
                    ? "文档" + documentId : String.valueOf(name);
        } catch (Exception e) {
            log.warn("G1 入库文档名取数失败 documentId={} err={}", documentId, e.getMessage());
            return "文档" + documentId;
        }
    }

    @Override
    @Transactional
    public void ingestCase(CaseIngestEventMessage message) {
        Map<String, Object> detail = coreGateway.getCase(message.caseId());
        if (detail == null || detail.isEmpty()) {
            log.info("G1 用例入库跳过：详情不可达 caseId={}（等待重投）", message.caseId());
            return;
        }
        String title = resolveCaseTitle(detail, message.caseId());
        String lang = resolveProjectLang(message.projectId());
        String assembled = DesensitizeUtils.maskText(assembleCaseText(detail));

        long docId = upsertLedger(SOURCE_TYPE_CASE, message.projectId(), message.caseId(),
                title, assembled);

        vectorStore.delete(KnowledgeMetadataFields.CASE_ID + " == " + message.caseId());
        addInBatches(List.of(buildChunkDocument(docId, message.projectId(), 1, title,
                assembled, lang, SOURCE_TYPE_CASE, message.caseId(), resolveExcellent(detail))));
        log.info("G1 用例入库完成 caseId={} version={} docId={} lang={}",
                message.caseId(), message.caseVersion(), docId, lang);
    }

    @Override
    @Transactional
    public void ingestRequirement(CaseIngestEventMessage message) {
        CoreGateway.RequirementBrief brief = coreGateway.getRequirement(message.requirementId());
        if (brief == null) {
            log.info("需求入库跳过：详情不可达 requirementId={}（等待重投/对账收敛）", message.requirementId());
            return;
        }
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "标题", brief.title());
        appendLine(sb, "验收标准", brief.acceptanceCriteria());
        String assembled = DesensitizeUtils.maskText(sb.toString());
        if (assembled.isBlank()) {
            log.info("需求入库跳过：标题与验收标准均空 requirementId={}（无内容可入库）", message.requirementId());
            return;
        }
        String title = brief.title() == null || brief.title().isBlank()
                ? "需求" + message.requirementId() : brief.title();
        String lang = resolveProjectLang(message.projectId());
        long docId = upsertLedger(SOURCE_TYPE_REQUIREMENT, message.projectId(),
                message.requirementId(), title, assembled);
        vectorStore.delete(KnowledgeMetadataFields.DOC_ID + " == " + docId);
        addInBatches(List.of(buildChunkDocument(docId, message.projectId(), 1, title,
                assembled, lang, SOURCE_TYPE_REQUIREMENT, 0L, false)));
        log.info("需求入库完成 requirementId={} version={} docId={} lang={}",
                message.requirementId(), message.requirementVersion(), docId, lang);
    }

    @Override
    @Transactional
    public void retireRequirement(long requirementId) {
        List<Long> activeDocIds = jdbcTemplate.queryForList(
                "SELECT id FROM tp_knowledge_doc WHERE source_type = :sourceType"
                        + " AND source_ref = :requirementId"
                        + " AND status = '" + KnowledgeMetadataFields.ACTIVE + "'",
                new MapSqlParameterSource().addValue("sourceType", SOURCE_TYPE_REQUIREMENT)
                        .addValue("requirementId", requirementId),
                Long.class);
        if (activeDocIds.isEmpty()) {
            log.info("需求下线零动作 requirementId={}（未入库/已下线，天然幂等 no-op）", requirementId);
            return;
        }
        for (Long docId : activeDocIds) {
            vectorStore.delete(KnowledgeMetadataFields.DOC_ID + " == " + docId);
        }
        int deactivated = jdbcTemplate.update(
                "UPDATE tp_knowledge_doc SET status = '" + KnowledgeMetadataFields.INACTIVE + "',"
                        + " updated_at = now() WHERE source_type = :sourceType"
                        + " AND source_ref = :requirementId"
                        + " AND status = '" + KnowledgeMetadataFields.ACTIVE + "'",
                new MapSqlParameterSource().addValue("sourceType", SOURCE_TYPE_REQUIREMENT)
                        .addValue("requirementId", requirementId));
        log.info("需求下线完成 requirementId={} 台账置INACTIVE={}（向量删净，检索不可达封自污染环）",
                requirementId, deactivated);
    }

    
    @Override
    @Transactional
    public void applyExcellentFlag(CaseIngestEventMessage message) {
        Map<String, Object> detail = coreGateway.getCase(message.caseId());
        if (detail == null || detail.isEmpty()) {
            log.info("D3 优秀联动跳过：详情不可达 caseId={}（等待重投/对账收敛）", message.caseId());
            return;
        }
        boolean excellent = resolveExcellent(detail);
        List<Long> activeDocIds = jdbcTemplate.queryForList(
                "SELECT id FROM tp_knowledge_doc WHERE source_type = :sourceType"
                        + " AND source_ref = :caseId AND status = '" + KnowledgeMetadataFields.ACTIVE + "'",
                new MapSqlParameterSource().addValue("sourceType", SOURCE_TYPE_CASE)
                        .addValue("caseId", message.caseId()),
                Long.class);
        if (!activeDocIds.isEmpty()) {
            int updated = metadataUpdater.updateCaseMetadata(
                    KnowledgeMetadataFields.EXCELLENT, String.valueOf(excellent), message.caseId());
            log.info("D3 优秀联动直改 metadata caseId={} excellent={} 向量行={}（不重入向量化）",
                    message.caseId(), excellent, updated);
            return;
        }
        Object status = detail.get("status");
        if (status instanceof Number n && n.intValue() == CASE_STATUS_APPROVED) {
            if (!kbGate.isAutoIngestEnabled(message.projectId())) {
                log.info("D3 优秀联动跳过兜底入库：项目知识库开关=关 caseId={} projectId={}（无 ACTIVE 行受闸）",
                        message.caseId(), message.projectId());
                return;
            }
            log.info("D3 优秀联动：无 ACTIVE 台账但用例已通过 caseId={} → 兜底重入库（带真值进）",
                    message.caseId());
            ingestCase(message);
            return;
        }
        log.info("D3 优秀联动跳过：用例未入库且非已通过 caseId={} status={}（无可联动对象）",
                message.caseId(), status);
    }

    
    private static boolean resolveExcellent(Map<String, Object> detail) {
        Object excellent = detail.get("excellent");
        return excellent instanceof Number n ? n.intValue() == 1 : Boolean.parseBoolean(String.valueOf(excellent));
    }

    
    private String resolveCaseTitle(Map<String, Object> detail, long caseId) {
        Object title = detail.get("title");
        if (title != null && !String.valueOf(title).isBlank()) {
            return String.valueOf(title);
        }
        Object caseNo = detail.get("caseNo");
        return caseNo == null || String.valueOf(caseNo).isBlank()
                ? "用例" + caseId : String.valueOf(caseNo);
    }

    
    private String assembleCaseText(Map<String, Object> detail) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "标题", detail.get("title"));
        appendLine(sb, "前置条件", detail.get("precondition"));
        appendLine(sb, "预期结果", detail.get("expectedResult"));
        if (detail.get("steps") instanceof List<?> steps) {
            int fallbackNo = 1;
            for (Object step : steps) {
                if (step instanceof Map<?, ?> map) {
                    Object no = map.get("stepNo") == null ? fallbackNo : map.get("stepNo");
                    appendLine(sb, "步骤" + no + (Integer.valueOf(1).equals(map.get("isAssert"))
                            || "1".equals(String.valueOf(map.get("isAssert"))) ? "（断言）" : ""),
                            map.get("description"));
                }
                fallbackNo++;
            }
        }
        try {
            List<Map<String, Object>> tags = coreGateway.getCaseTags(
                    ((Number) detail.get("id")).longValue());
            if (tags != null && !tags.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (Map<String, Object> tag : tags) {
                    if (names.length() > 0) {
                        names.append('、');
                    }
                    names.append(tag.get("name"));
                }
                appendLine(sb, "标签", names.toString());
            }
        } catch (Exception e) {
            log.warn("G1 用例标签取数失败降级空标签不阻断 caseId={} err={}",
                    detail.get("id"), e.getMessage());
        }
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String label, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            sb.append(label).append("：").append(value).append('\n');
        }
    }

    
    private String resolveProjectLang(long projectId) {
        return langResolver.resolve(projectId);
    }

    
    private long upsertLedger(String sourceType, long projectId, long sourceRef,
                              String title, String content) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sourceType", sourceType)
                .addValue("projectId", projectId)
                .addValue("sourceRef", sourceRef)
                .addValue("title", title)
                .addValue("content", content);
        List<Long> existing = jdbcTemplate.queryForList(
                "SELECT id FROM tp_knowledge_doc WHERE source_type = :sourceType"
                        + " AND project_id = :projectId AND source_ref = :sourceRef",
                params, Long.class);
        if (!existing.isEmpty()) {
            // Re-ingest of the same source is a new ledger version (V339): the
            // counter rides the row so citations can name the version that
            // backed an answer. Only content rewrites bump it — status-only
            // updates elsewhere keep the revision untouched.
            jdbcTemplate.update(
                    "UPDATE tp_knowledge_doc SET title = :title, content = :content,"
                            + " status = 'ACTIVE', revision = revision + 1,"
                            + " updated_at = now() WHERE id = :id",
                    params.addValue("id", existing.get(0)));
            return existing.get(0);
        }
        Long inserted = jdbcTemplate.queryForObject(
                "INSERT INTO tp_knowledge_doc (project_id, pack_id, source_type, source_ref,"
                        + " title, content, status)"
                        + " VALUES (:projectId, 0, :sourceType, :sourceRef, :title, :content, 'ACTIVE')"
                        + " RETURNING id",
                params, Long.class);
        if (inserted == null) {
            throw new IllegalStateException("台账插入无返回 id sourceRef=" + sourceRef);
        }
        return inserted;
    }

    private Document buildDocument(long docId, long projectId, Map<String, Object> rawChunk,
                                   String maskedContent, String docTitle, String lang, int index,
                                   String sourceType) {
        int seq = rawChunk.get("seq") == null ? index + 1 : (int) Long.parseLong(
                String.valueOf(rawChunk.get("seq")));
        Object chapter = rawChunk.get("chapter");
        return buildChunkDocument(docId, projectId, seq,
                chapter == null || String.valueOf(chapter).isBlank() ? docTitle : String.valueOf(chapter),
                maskedContent, lang, sourceType, 0L, false);
    }

    
    private Document buildChunkDocument(long docId, long projectId, int seq, String title,
                                        String maskedContent, String lang,
                                        String sourceType, long caseId, boolean excellent) {
        return KnowledgeChunkDocumentFactory.create(docId, projectId, seq, title,
                maskedContent, lang, sourceType, caseId, excellent);
    }

    @Override
    public void cleanupCase(long caseId) {
        vectorStore.delete(KnowledgeMetadataFields.CASE_ID + " == " + caseId);
        int deactivated = jdbcTemplate.update(
                "UPDATE tp_knowledge_doc SET status = '" + KnowledgeMetadataFields.INACTIVE + "',"
                        + " updated_at = now() WHERE source_type = '" + SOURCE_TYPE_CASE + "'"
                        + " AND source_ref = :caseId AND status = '" + KnowledgeMetadataFields.ACTIVE + "'",
                new MapSqlParameterSource("caseId", caseId));
        log.info("G1 用例清理完成 caseId={} 台账置INACTIVE={}（无台账行=未入库/重复清理，天然幂等 no-op）",
                caseId, deactivated);
    }

    @Override
    public void cleanupProject(long projectId) {
        vectorStore.delete(KnowledgeMetadataFields.PROJECT_ID + " == " + projectId);
        int deactivated = jdbcTemplate.update(
                "UPDATE tp_knowledge_doc SET status = '" + KnowledgeMetadataFields.INACTIVE + "',"
                        + " updated_at = now() WHERE project_id = :projectId"
                        + " AND status = '" + KnowledgeMetadataFields.ACTIVE + "'",
                new MapSqlParameterSource("projectId", projectId));
        log.info("G1 项目级清理完成 projectId={} 台账置INACTIVE={}（TC-G1-04）", projectId, deactivated);
    }

    /** Batched embedding insert (K8 batch-size hot-tunable, 14 §3.5 default 128 docs/request; split single-sourced in ListPartition). */
    private void addInBatches(List<Document> documents) {
        int batch = batchSizeResolver.resolve();
        for (List<Document> chunk : ListPartition.of(documents, batch)) {
            vectorStore.add(chunk);
        }
    }
}
